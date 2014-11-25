package com.blinkbox.books.storageservice

import java.util.concurrent.atomic.AtomicReference
import akka.actor.ActorRef
import akka.actor.FSM.->
import com.blinkbox.books.config.{ApiConfig, Configuration}
import com.blinkbox.books.json.DefaultFormats
import com.blinkbox.books.rabbitmq.RabbitMqConfig
import com.fasterxml.jackson.core.JsonProcessingException
import com.typesafe.config.Config
import org.json4s.jackson.JsonMethods._
import org.json4s.{FieldSerializer, JValue}
import org.junit.runner.RunWith
import org.mockito.AdditionalMatchers.aryEq
import org.mockito.Matchers.any
import com.blinkbox.books.test.MatcherSugar.eql
import org.mockito.Mockito.{atLeastOnce, never, reset, times, verify, when}
import org.mockito.invocation
import org.mockito.invocation.InvocationOnMock
import org.mockito.stubbing
import org.mockito.stubbing.Answer
import org.scalacheck.Arbitrary.arbitrary
import org.scalacheck.{Shrink, Gen}
import org.scalacheck.Gen.alphaStr
import org.scalatest.concurrent.PatienceConfiguration.Timeout
import org.scalatest.concurrent.{AsyncAssertions, ScalaFutures}
import org.scalatest.junit.JUnitRunner
import org.scalatest.mock.MockitoSugar
import org.scalatest.prop.GeneratorDrivenPropertyChecks
import org.scalatest.time.{Seconds, Millis, Span}
import org.scalatest.{FlatSpecLike, Matchers}
import spray.http.StatusCodes._
import spray.http._
import spray.testkit.ScalatestRouteTest
import spray.util.NotImplementedException
import scala.concurrent.Future
import scala.util.Random
import org.json4s.JsonDSL._
import scala.util.matching.Regex

@RunWith(classOf[JUnitRunner])
class QuarterMasterSpecification extends Configuration with FlatSpecLike with ScalatestRouteTest
with Matchers with GeneratorDrivenPropertyChecks with ScalaFutures with  akka.testkit.TestKitBase with AsyncAssertions {
  val minlabel = 0
  val maxlabel = 3
  val initMapping: Mapping = Mapping(List())

  val nonEmptyAlphaNumeric  = Gen.nonEmptyListOf(Gen.alphaNumChar).map(_.mkString)
  val labelGen = for {
    labelNum:Int <- Gen.chooseNum(minlabel, maxlabel)
  } yield labelNum.toString

  val genProviderId2TemplateTuple= for {
    providerId <- nonEmptyAlphaNumeric
    template <- nonEmptyAlphaNumeric
  } yield (providerId, template)

  val genProviderIdToTemplateMap = Gen.mapOf(genProviderId2TemplateTuple)
  val templateGen = for {
    providerId <- arbitrary[String]
    label <- labelGen
    template <- arbitrary[String]
    extractorRegex <-  Gen.alphaStr
    providerId2TemplateMap <- genProviderIdToTemplateMap
  } yield ProviderConfig(label, extractorRegex, providerId2TemplateMap )

  val templateGenDirectToJson: Gen[JValue] = for {
    providerId <- nonEmptyAlphaNumeric
    label <- nonEmptyAlphaNumeric
    template <- nonEmptyAlphaNumeric
    extractorRegex <- nonEmptyAlphaNumeric
  } yield ("label" -> label)  ~ ("extractor" -> extractorRegex) ~ ("providers" -> (providerId -> template))

  val mappingGen2 = for {
    numElem <- Gen.chooseNum(0,10)
    templateList <- Gen.listOfN(numElem,templateGenDirectToJson)
  } yield  templateList

  val mappingGen = for {
    templateList <- Gen.listOf(templateGen)
  } yield Mapping(templateList)

  def templateForProvidersAndLabel(providers: Set[StorageProvider], label: String):Gen[ProviderConfig] = {
    val differentLabel = label + System.nanoTime()
    val providerIds = providers.map(_.providerId).toSeq
    for {
      containsProvider <- arbitrary[Boolean]
      resultLabel <- Gen.oneOf(label, differentLabel)
      matchingProviderId <- Gen.oneOf(providerIds)
      resultProviderId <- Gen.oneOf(matchingProviderId, "dummyProviderId" + System.nanoTime())
      template <- arbitrary[String]
      extractorRegex <- Gen.alphaStr
    } yield ProviderConfig(resultLabel,  extractorRegex, Map(resultProviderId -> template))
  }

  def genMappingForProvidersAndLabel(providers: Set[StorageProvider], label: String) : Gen[Mapping] = for {
    urlTemplateList <- Gen.listOf(templateForProvidersAndLabel(providers, label))
  } yield Mapping(urlTemplateList)

  val mockSuccessfulProviderGen = for {
    labels <- Gen.listOf(labelGen)
    providerId = "mockingProvider" +System.nanoTime
  } yield getSuccessfulProvider(providerId)

  val mockSuccessfulProviderSetGen = for {
    successfulProviders <- Gen.listOf(mockSuccessfulProviderGen)
  } yield successfulProviders.toSet

  implicit val formats = DefaultFormats + FieldSerializer[Mapping]() + FieldSerializer[ProviderConfig]()

  val mappingJsonStr = """{"extractor":"^.*/(?P<filename>$.*)\\.(?P<extenstion>.{2,3})\\?",
      "templates":[{
      "providerId":"azure-a",
      "template":"http://azureservices.com/blinkbox/\\g<filename>.\\g<extenstion>"}]}"""

  val appConfig = AppConfig(MappingConfig(config), MockitoSugar.mock[RabbitMqConfig], Set(MockitoSugar.mock[Config]),  ApiConfig(config, AppConfig.apiConfigKey))

  def failingWriteAnswer(e: Throwable): Answer[Future[(ProviderId, Status)]] = new Answer[Future[(ProviderId, Status)]] {
    override def answer(invocation: InvocationOnMock): Future[(ProviderId, Status)] = Future.failed(e)
  }

  def successfulWriteAnswer(providerId: ProviderId): Answer[Future[(ProviderId, Status)]] = new Answer[Future[(ProviderId, Status)]] {
    override def answer(invocation: InvocationOnMock): Future[(ProviderId, Status)] = {
      invocation.getArguments.head match {
        case assetDigestArg: AssetDigest => Future {
          (providerId, Status.finished)
        }
      }
    }
  }

  def getSuccessfulProvider(providerId: String):StorageProvider = {
    val mockStorageDao = MockitoSugar.mock[StorageDao]
    val mockRepo = MockitoSugar.mock[StorageProviderRepo]
    when(mockRepo.getStatus(any[JobId])).thenReturn(Future.successful(Status.notFound))
    when(mockRepo.updateProgress(any[JobId],any[Long], any[DateTime], any[Long])).thenReturn(Future.successful(()))
    when(mockRepo.removeProgress(any[JobId])).thenReturn(Future.successful(()))
    when(mockStorageDao.write(any[AssetDigest], any[Array[Byte]])).thenReturn(Future.successful(()))
    when(mockStorageDao.providerId).thenReturn(providerId)
    StorageProvider(mockRepo, mockStorageDao)
  }

  def genSuccessfulProvider = for {
    providerIdSuffix <- arbitrary[String]
  } yield getSuccessfulProvider("Successful:"+providerIdSuffix)

  def getFailingProvider(providerId: String, e: Exception) = {
    val mockStorageDao = MockitoSugar.mock[StorageDao]
    val mockRepo = MockitoSugar.mock[StorageProviderRepo]
    when(mockRepo.getStatus(any[JobId])).thenReturn(Future.successful(Status.notFound))
    when(mockRepo.updateProgress(any[JobId],any[Long], any[DateTime], any[Long])).thenReturn(Future.successful(()))
    when(mockRepo.removeProgress(any[JobId])).thenReturn(Future.successful(()))
    when(mockStorageDao.providerId).thenReturn(providerId)
    when(mockStorageDao.cleanUp(any[AssetDigest])).thenAnswer(new Answer[Future[Unit]] {
      override def answer(invocation: InvocationOnMock): Future[Unit] = {Future.successful(())}
    })
    when(mockStorageDao.write(any[AssetDigest], any[Array[Byte]])).thenAnswer(new Answer[Future[Unit]] {
      override def answer(invocation: InvocationOnMock): Future[Unit] = { Future.failed(e)}
    })
    StorageProvider(mockRepo, mockStorageDao)
  }

  def genFailingProvider = for {
    providerIdSuffix <- arbitrary[String]
    exception <- arbitrary[Exception]
  } yield getFailingProvider("Failing:"+providerIdSuffix, exception)


   "the quartermaster service " should " send json representation of the mapping file " in {
       val result = MappingHelper(MockitoSugar.mock[MappingLoader]).toJson(Mapping(List(ProviderConfig("azureEpubLabel", "bbbmap(.*)", Map("azureProvider" -> "myTemplate")))))
       result shouldEqual """[{"label":"azureEpubLabel","extractor":"bbbmap(.*)","providers":{"azureProvider":"myTemplate"}}]"""
   }

 "The quarterMasterService" should " update the mapping file " in {
   forAll(mappingGen, mappingGen2) { (oldMapping, newMapping) => {
       val mockMappingLoader = new MappingLoader{
         override def load(path: String): String = throw new IllegalStateException()
         override def write(path: String, json: String): Unit = ()
       }
       val expected= compact(render(newMapping))
       val mockSender = MockitoSugar.mock[MessageSender]
       when(mockSender.broadcastUpdate(any[Mapping])).thenReturn(Future.successful(()))
       val mockRepo = MockitoSugar.mock[StorageProviderRepo]
       val storageManager  = StorageManager(mockRepo, oldMapping, Set())
       val mappingHelper = MappingHelper(mockMappingLoader)
       val qms = new QuarterMasterService(appConfig,  mockSender, storageManager, mappingHelper)
       val f = qms.updateAndBroadcastMapping(expected)
       whenReady(f,Timeout(Span(1, Seconds)))(_ == expected)
     }
   }
 }

 "The quarterMasterService" should "not update the mapping with bad json " in {
    forAll(mappingGen, alphaStr) { (oldMapping, json) =>
      val mockRepo = MockitoSugar.mock[StorageProviderRepo]
      val mockSender = MockitoSugar.mock[MessageSender]
      val storageManager  = StorageManager(mockRepo, oldMapping, Set())
      val mappingHelper = MappingHelper(new FileMappingLoader)
      val qms = new QuarterMasterService(appConfig, mockSender, storageManager, mappingHelper)
      val f = qms.updateAndBroadcastMapping(json)
      whenReady(f.failed, Timeout(Span(1, Seconds))) {
        e => e shouldBe a [JsonProcessingException]
      }
    }
 }
 "The quarterMasterService" should "not load bogus data " in {
    forAll(mappingGen, alphaStr) { (oldMapping, bogusMapping) =>
      val mockRepo = MockitoSugar.mock[StorageProviderRepo]
      val mockSender = MockitoSugar.mock[MessageSender]
      val storageManager  = StorageManager(mockRepo, oldMapping, Set())
      val mockMappingLoader = MockitoSugar.mock[MappingLoader]
      val mappingHelper = MappingHelper(mockMappingLoader)
      when(mockMappingLoader.load(any[String])).thenReturn(bogusMapping)
      val qms = new QuarterMasterService(appConfig, mockSender, storageManager, mappingHelper)
      val f = qms.loadMapping
      whenReady(f.failed, Timeout(Span(1, Seconds))) {
        e => e shouldBe a [JsonProcessingException]
      }
    }
 }

  "The quarterMasterService " should " load good data " in {
    implicit val noShrink: Shrink[Int] = Shrink.shrinkAny
     forAll(mappingGen, mappingGen2) { (oldMapping, loaded) =>
       val loadStr = compact(render(loaded))
       val mockRepo = MockitoSugar.mock[StorageProviderRepo]
       val mockSender = MockitoSugar.mock[MessageSender]
       val storageManager  = StorageManager(mockRepo, oldMapping, Set())
       val mockMappingLoader = MockitoSugar.mock[MappingLoader]
       val mappingHelper = MappingHelper(mockMappingLoader)
       when(mockMappingLoader.load(any[String])).thenReturn(loadStr)
       val qms = new QuarterMasterService(appConfig, mockSender, storageManager, mappingHelper)
       val f = qms.loadMapping
       println(loaded)
       whenReady(f, Timeout(Span(50, Seconds)) )((s) => {
         s shouldEqual loadStr
       })
     }
  }

 def genProvidersLabelAndMapping = for {
   successfulProviders <- Gen.nonEmptyListOf(genSuccessfulProvider)
   failingProviders    <- Gen.listOf(genFailingProvider)
   providers = Random.shuffle(successfulProviders.toSet.union(failingProviders.toSet))
   label <- labelGen
   mapping <- genMappingForProvidersAndLabel(providers, label)
 } yield (providers, label , mapping)


 "the quarterMaster" should "clean up failed assets" in {
   val timeout = Timeout(Span(50, Seconds))
   forAll (genProvidersLabelAndMapping, Gen.listOf(arbitrary[Byte]))
     {
      (providersLabelMapping, dataList) => {
         val providers = providersLabelMapping._1
         val label = providersLabelMapping._2
         val mapping = providersLabelMapping._3
         val data = dataList.toArray
         val w = new Waiter
         val repo = MockitoSugar.mock[StorageProviderRepo]
       when(repo.updateProgress(any[JobId], any[Long], any[DateTime], any[Long])).thenReturn(Future.successful(()))
       when(repo.removeProgress(any[JobId])).thenReturn(Future.successful(()))
       when(repo.getStatus(any[JobId])).thenReturn(Future(Status.notFound))
       val storageManager = new StorageManager(repo, mapping, providers)
       val qms2 = new QuarterMasterService(appConfig, MockitoSugar.mock[MessageSender], storageManager, MockitoSugar.mock[MappingHelper])
       val callAccepted = qms2.storeAsset(data, label)
        val matchingProviders = (for {
          urlTemplate <- mapping.providers.filter(_.label == label)
          provider <- providers.filter(provider => urlTemplate.providers.keySet.contains(provider.providerId))
         } yield provider).toSet
         val nonMatchingProviders = providers.diff(matchingProviders)
         val matchingSuccessfulProviders = matchingProviders.filter(_.providerId.startsWith("Successful"))
         val matchingSuccessfulDaos = matchingSuccessfulProviders.map(_.dao)
       w{
          val f = callAccepted.flatMap(_._2)
          if (matchingProviders.size < 1) {
            whenReady(f.failed, timeout) {
               e => e shouldBe a[NotImplementedException]
               w.dismiss()
            }
          } else
          if (data.size < 1) {
            whenReady(f.failed, timeout) {
               e => e shouldBe a[IllegalArgumentException]
               w.dismiss()
            }
          }
          else
            whenReady(f, timeout)((s) => {
               val assetDigest = callAccepted.futureValue._1
               val matchingFailingDaos = matchingProviders.filter(_.providerId.startsWith("Failing")).map(_.dao)
               matchingSuccessfulDaos.map(verify(_, times(1)).write(eql(assetDigest), aryEq(data)))
               matchingSuccessfulDaos.map(verify(_, never).cleanUp(any[AssetDigest]))
               matchingFailingDaos.map(verify(_, times(1)).write(eql(assetDigest), aryEq(data)))
               matchingFailingDaos.map(verify(_, times(1)).cleanUp(any[AssetDigest]))
               w.dismiss()
            })
        }
       w.await()
     }
   }
 }

 it should "connect to the correct mappings" in  {
    val mappingRef = new AtomicReference[Mapping]
    mappingRef.set(initMapping)
    val mockSender = MockitoSugar.mock[MessageSender]
    val mockMappingHelper = MockitoSugar.mock[MappingHelper]
    val mockStorageManager  = MockitoSugar.mock[StorageManager]
    when(mockStorageManager.mapping).thenReturn(mappingRef)
    val qms = new QuarterMasterService(appConfig, mockSender, mockStorageManager, mockMappingHelper)
    val router = new QuarterMasterRoutes(qms, createActorSystem())
    def routes = router.routes
    Get("/mappings") ~> routes ~> check {
      assert(status == OK )
      mediaType.toString == "application/vnd.blinkbox.books.mapping.update.v1+json"
    }
 }

 it should "connect reload the mapping path" in  {
    val mockSender = MockitoSugar.mock[MessageSender]
    val mockRepo = MockitoSugar.mock[StorageProviderRepo]
    val storageManager  = StorageManager(mockRepo, initMapping, Set())
    val mockMappingLoader = MockitoSugar.mock[MappingLoader]
    val mappingHelper = MappingHelper(new FileMappingLoader)
    val qms = new QuarterMasterService(appConfig,  mockSender, storageManager, mappingHelper)
    val router = new QuarterMasterRoutes(qms,createActorSystem())
    def routes = router.routes
    Put("/mappings/refresh") ~> routes ~> check {
      assert(status == OK )
      mediaType.toString == "application/vnd.blinkbox.books.mapping.update.v1+json"
    }
 }


 // it should " be able to parse regex" in {

 //                               //.suchThat(_.forall(_.isLetter))
 //   forAll(nonEmptyAlphaNumeric,nonEmptyAlphaNumeric, Gen.chooseNum(1,3))
 //   {
 //     (urlHead, urlTail, groupNum) =>
 //       val digest = AssetDigest(s"bbbmap:$urlHead:$urlTail")
 //       val regex = "(.*):(.*):(.*)"
 //       val template = ProviderConfig(Map("dummyProviderId" ->( """theNewUrlIs://\'"""+groupNum+"'")) , Label("dummyTemplate"), regex)
 //       val result = new Regex(regex).findFirstMatchIn(digest.url).map(m => """theNewUrlIs://"""+m.group(groupNum))
 //       template.createUrlFrom(digest) shouldEqual result
 //     }
 // }
}
