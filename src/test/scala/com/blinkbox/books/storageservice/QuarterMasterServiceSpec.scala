package com.blinkbox.books.storageservice

import java.util.concurrent.atomic.AtomicReference
import akka.testkit.{TestKit, EventFilter, ImplicitSender}
import com.blinkbox.books.config.Configuration
import com.blinkbox.books.json.DefaultFormats
import com.blinkbox.books.test.MatcherSugar.eql
import com.fasterxml.jackson.core.{JsonProcessingException, JsonParseException}
import com.fasterxml.jackson.databind.JsonMappingException
import org.json4s.JsonDSL._
import org.json4s.jackson.JsonMethods._
import org.json4s.{FieldSerializer, JValue}
import org.junit.runner.RunWith
import org.mockito.AdditionalMatchers.aryEq
import org.mockito.Matchers.any
import org.mockito.Mockito.{atLeastOnce, never, reset, times, verify, when}
import org.mockito.invocation.InvocationOnMock
import org.mockito.stubbing.Answer
import org.scalacheck.Arbitrary.arbitrary
import org.scalacheck.Gen
import org.scalacheck.Gen.alphaStr
import org.scalacheck.Prop.BooleanOperators
import org.scalatest.concurrent.{AsyncAssertions, ScalaFutures}
import org.scalatest.junit.JUnitRunner
import org.scalatest.mock.MockitoSugar
import org.scalatest.prop.GeneratorDrivenPropertyChecks
import org.scalatest.{FlatSpecLike, Matchers}
import spray.http.StatusCodes._
import spray.http._
import akka.testkit.{EventFilter, ImplicitSender, TestKit}
import spray.httpx.marshalling.MetaMarshallers
import spray.testkit.ScalatestRouteTest
import spray.util.NotImplementedException
import scala.concurrent.Future
import scala.util.Random
import org.scalacheck.Shrink

import scala.util.control.NonFatal

@RunWith(classOf[JUnitRunner])
class QuarterMasterSpecification extends Configuration with FlatSpecLike with ScalatestRouteTest
with Matchers with GeneratorDrivenPropertyChecks with ScalaFutures with  akka.testkit.TestKitBase with AsyncAssertions {
  val minlabel = 0
  val maxlabel = 3
  val initMapping: Mapping = Mapping(List())

  val labelGen = for {
    labelNum:Int <- Gen.chooseNum(minlabel, maxlabel)
  } yield Label(labelNum.toString)

  val templateGen = for {
    serviceName <- arbitrary[String]
    label <- labelGen
    template <- arbitrary[String]
    regexStrLen <- Gen.chooseNum(0,50)
  } yield UrlTemplate(ServiceName(serviceName), label,  template, Random.alphanumeric.take(regexStrLen).mkString )

  val templateGenDirectToJson: Gen[JValue] = for {
    serviceName <- arbitrary[String]
    template <- arbitrary[String]
  } yield ("serviceName" -> serviceName) ~ ("template" -> template)

  val mappingGen2 = for {
    extractor <- arbitrary[String]
    templateList <- Gen.listOf(templateGenDirectToJson)
  } yield ("extractor" -> extractor) ~ ("templates" -> templateList)

  val mappingGen = for {
    templateList <- Gen.listOf(templateGen)
    extractor <- arbitrary[String]
  } yield Mapping(templateList)

  val mockSuccessfulProviderGen = for {
    labels <- Gen.listOf(labelGen)
    serviceName = ServiceName("mockingProvider" +System.nanoTime)
  } yield getSuccessfulProvider(serviceName, successfulWriteAnswer(serviceName))

  val mockSuccessfulProviderSetGen = for {
    successfulProviders <- Gen.listOf(mockSuccessfulProviderGen)
  } yield successfulProviders.toSet

  implicit val formats = DefaultFormats + FieldSerializer[Mapping]() + FieldSerializer[UrlTemplate]()

  val mappingJsonStr = """{"extractor":"^.*/(?P<filename>$.*)\\.(?P<extenstion>.{2,3})\\?",
      "templates":[{
      "serviceName":"azure-a",
      "template":"http://azureservices.com/blinkbox/\\g<filename>.\\g<extenstion>"}]}"""

  val appConfig = AppConfig(config, MockitoSugar.mock[BlinkboxRabbitMqConfig], MockitoSugar.mock[LocalStorageConfig])
  MappingHelper.loader = new MappingLoader {
    override def load(path: String): String = mappingJsonStr
    override def write(path: String, json: String): Unit = ()
  }

  def failingWriteAnswer(e: Throwable): Answer[Future[(ServiceName, Status)]] = new Answer[Future[(ServiceName, Status)]] {
    override def answer(invocation: InvocationOnMock): Future[(ServiceName, Status)] = Future.failed(e)
  }

  def successfulWriteAnswer(serviceName: ServiceName): Answer[Future[(ServiceName, Status)]] = new Answer[Future[(ServiceName, Status)]] {
    override def answer(invocation: InvocationOnMock): Future[(ServiceName, Status)] = {
      invocation.getArguments.head match {
        case assetDigestArg: AssetDigest => Future {
          (serviceName, Status.finished)
        }
      }
    }
  }

  def getSuccessfulProvider(serviceName: ServiceName, answer: Answer[Future[(ServiceName, Status)]]):StorageProvider = {
    val mockStorageDao = MockitoSugar.mock[StorageDao]
    val mockRepo = MockitoSugar.mock[StorageProviderRepo]
    val provider =new StorageProvider(mockRepo, mockStorageDao)
    when(mockRepo.getStatus(any[JobId])).thenReturn(Future.successful(Status.notFound))
    when(mockRepo.updateProgress(any[JobId],any[Long], any[DateTime], any[Long])).thenReturn(Future.successful(()))
    when(mockRepo.removeProgress(any[JobId])).thenReturn(Future.successful(()))
    when(mockStorageDao.write(any[AssetDigest], any[Array[Byte]])).thenAnswer(new Answer[Future[Unit]] {
      override def answer(invocation: InvocationOnMock): Future[Unit] = {Future.successful(())}
    })
    provider
  }

  def getFailingProvider(serviceName: ServiceName, e: Exception) = {
    val mockStorageDao = MockitoSugar.mock[StorageDao]
    val mockRepo = MockitoSugar.mock[StorageProviderRepo]
    val provider =new StorageProvider(mockRepo, mockStorageDao)
    when(mockRepo.getStatus(any[JobId])).thenReturn(Future.successful(Status.notFound))
    when(mockRepo.updateProgress(any[JobId],any[Long], any[DateTime], any[Long])).thenReturn(Future.successful(()))
    when(mockRepo.removeProgress(any[JobId])).thenReturn(Future.successful(()))
    when(mockStorageDao.cleanUp(any[AssetDigest])).thenAnswer(new Answer[Future[Unit]] {
      override def answer(invocation: InvocationOnMock): Future[Unit] = {Future.successful(())}
    })
    when(mockStorageDao.write(any[AssetDigest], any[Array[Byte]])).thenAnswer(new Answer[Future[Unit]] {
      override def answer(invocation: InvocationOnMock): Future[Unit] = { Future.failed(e)}
    })
    provider
  }

  "The quarterMasterService" should "update the mapping file " in {
    forAll(mappingGen, mappingGen2) { (oldMapping, newMapping) =>
      (!newMapping.extract[Mapping].templates.isEmpty || true) ==> {
        val json = newMapping.toString
        val expected= compact(render(newMapping))
        val mockSender = MockitoSugar.mock[MessageSender]
        val mockStorageManager  = MockitoSugar.mock[StorageManager]
        val qms = new QuarterMasterService(appConfig,  mockSender, mockStorageManager)
        val f = qms.updateAndBroadcastMapping(expected)
        whenReady(f)(_ == expected)
      }
    }
  }

  "The quarterMasterService" should "not update the mapping with bad json " in {
    forAll(mappingGen, alphaStr) { (oldMapping, json) =>
      val mockRepo = MockitoSugar.mock[StorageProviderRepo]
      val mockSender = MockitoSugar.mock[MessageSender]
      val storageManager  = StorageManager(mockRepo, oldMapping, Set())
      val qms = new QuarterMasterService(appConfig, mockSender, storageManager)
      val expected =MappingHelper.toJson(oldMapping)
      val f = qms.updateAndBroadcastMapping(json)
      whenReady(f.failed) {
        e => e shouldBe a [JsonProcessingException]
      }
    }
  }

  "The quarterMasterService" should "not load bogus data " in {
    forAll(mappingGen, alphaStr) { (oldMapping, bogusMapping) =>
      MappingHelper.loader = new MappingLoader {
        override def load(path: String): String = bogusMapping
        override def write(path: String, json:String): Unit= ()
      }
      val mockRepo = MockitoSugar.mock[StorageProviderRepo]
      val mockSender = MockitoSugar.mock[MessageSender]
      val storageManager  = StorageManager(mockRepo, oldMapping, Set())
      val qms = new QuarterMasterService(appConfig, mockSender, storageManager)
      val expected =MappingHelper.toJson(oldMapping)
      val f = qms.loadMapping
      whenReady(f.failed) {
        e => e shouldBe a [JsonProcessingException]
      }
    }
  }

  "The quarterMasterService" should "  load good data " in {
    forAll(mappingGen, mappingGen2) { (oldMapping, loaded) =>
      val loadStr = compact(render(loaded))
      MappingHelper.loader = new MappingLoader {
        override def load(path: String): String = loadStr
        override def write(path: String, json:String): Unit= ()
      }
      val mockRepo = MockitoSugar.mock[StorageProviderRepo]
      val mockSender = MockitoSugar.mock[MessageSender]
      val storageManager  = StorageManager(mockRepo, oldMapping, Set())
      val qms = new QuarterMasterService(appConfig, mockSender, storageManager)
      val expected =MappingHelper.toJson(oldMapping)
      val f = qms.loadMapping
      whenReady(f)((s) => {
        s shouldEqual loadStr
      })
    }
  }

//  val  mockFailingProviderConfigGen  = for {
//    labels <- Gen.listOf(labelGen)
//    serviceName = ServiceName("mockingProvider" +System.nanoTime)
//  } yield new ProviderConfig(getFailingProvider(serviceName, new IllegalStateException), labels.toSet)
//
//  "the quarterMaster" should "clean up failed assets" in {
//    val label = Label("2:2")
//    val labeledFailingProviderConfigGen = for {
//      labels <- Gen.listOf(labelGen)
//      serviceName = ServiceName("mockingProvider" + System.nanoTime)
//    } yield new ProviderConfig(getFailingProvider(serviceName, new IllegalStateException), labels.toSet.+(label))
//    forAll(Gen.listOf(mockSuccessfulProviderConfigGen), Gen.nonEmptyListOf(labeledFailingProviderConfigGen),
//      Gen.nonEmptyListOf(arbitrary[Byte])
//    ) {
//      (successfulProviderSet, mockFailingProviderSet, dataList) => {
//        val w = new Waiter
//        val data = dataList.toArray
//        val repo = MockitoSugar.mock[StorageProviderRepo]
//        when(repo.updateProgress(any[JobId], any[Long], any[DateTime], any[Long])).thenReturn(Future.successful(()))
//        when(repo.removeProgress(any[JobId])).thenReturn(Future.successful(()))
//        when(repo.getStatus(any[JobId])).thenReturn(Future(Status.notFound))
//        val randomSuccessAndFailingWriterConfigs = Random.shuffle(successfulProviderSet.toSet.union(mockFailingProviderSet.toSet))
//        val storageManager = new StorageManager(repo, initMapping, randomSuccessAndFailingWriterConfigs.toSet)
//        val newConfig = AppConfig(config, appConfig.rmq, appConfig.lsc)
//        val qms2 = new QuarterMasterService(newConfig, initMapping, MockitoSugar.mock[MessageSender], storageManager)
//        val callAccepted = qms2.storeAsset(data, label)
//        w{
//        val f = callAccepted.flatMap(_._2)
//        whenReady(f)((s) =>  {
//          val assetDigest = callAccepted.futureValue._1
//          val matchingSuccessfulDaos = successfulProviderSet.filter(_.labels.contains(label)).map(_.provider.dao)
//          val matchingFailingDaos = mockFailingProviderSet.filter(_.labels.contains(label)).map(_.provider.dao)
//          matchingSuccessfulDaos.map(verify(_, times(1)).write(eql(assetDigest), aryEq(data)))
//          matchingSuccessfulDaos.map(verify(_, never).cleanUp(any[AssetDigest]))
//          matchingFailingDaos.map(verify(_, times(1)).write(eql(assetDigest), aryEq(data)))
//          matchingFailingDaos.map(verify(_, times(1)).cleanUp(any[AssetDigest]))
//          w.dismiss()
//        })}
//        w.await()
//      }
//    }
//  }
  it should "connect to the correct mappings" in  {
    val mappingRef = new AtomicReference[Mapping]
    mappingRef.set(initMapping)
    val mockSender = MockitoSugar.mock[MessageSender]
    val mockStorageManager  = MockitoSugar.mock[StorageManager]
    when(mockStorageManager.mapping).thenReturn(mappingRef)
    val qms = new QuarterMasterService(appConfig, mockSender, mockStorageManager)

    val router = new QuarterMasterRoutes(qms, createActorSystem())
    def routes = router.routes
    Get("/mappings") ~> routes ~> check {
      assert(status == OK )
      mediaType.toString == "application/vnd.blinkbox.books.mapping.update.v1+json"
    }
  }

  it should "connect reload the mapping path" in  {
    val mockSender = MockitoSugar.mock[MessageSender]
    val mockStorageManager  = MockitoSugar.mock[StorageManager]
    val qms = new QuarterMasterService(appConfig,  mockSender, mockStorageManager)
    val router = new QuarterMasterRoutes(qms,createActorSystem())
    def routes = router.routes
    Put("/mappings/refresh") ~> routes ~> check {
      assert(status == OK )
      mediaType.toString == "application/vnd.blinkbox.books.mapping.update.v1+json"
    }
  }

//  it should "save an artifact" in {
//    val label = Label("2")
//    forAll(Gen.nonEmptyListOf(mockSuccessfulProviderGen), Gen.nonEmptyListOf(arbitrary[Byte]) ) {
//      (mockProviderList, datalist) => {
//          val w = new Waiter
//          val mockProviderSet = mockProviderList.toSet
//          val data = datalist.toArray
//          val repo = new InMemoryRepo
//          val storageManager = new StorageManager(repo, initMapping, mockProviderSet)
//          val newConfig = AppConfig(config, appConfig.rmq, appConfig.lsc)
//          val mockSender = MockitoSugar.mock[MessageSender]
//          val service = new QuarterMasterService(newConfig, mockSender, storageManager)
//          val router = new QuarterMasterRoutes(service,createActorSystem())
//          def routes = router.routes
//          val compressible  = true
//          val binary = true
//          val contentType = ContentType(MediaType.custom("application", "epub+zip", compressible, binary, Seq[String]("epub"), Map.empty))
//          Post("/resources",
//            MultipartFormData(
//              Map(
//                "label" -> BodyPart(HttpEntity(ContentTypes.`text/plain`, label.label)),
//                "data" -> BodyPart(HttpEntity(contentType, HttpData(data)))
//              ))
//          ) ~> routes ~> check {
//            w{
//              assert(status == Accepted)
//              val matchingProviders: Set[StorageProvider] = mockProviderConfigSet.filter(_.labels.contains(label)).map(_.provider)
//              val nonMatchingProviders = mockProviderConfigSet.filter(!_.labels.contains(label)).map(_.provider)
//              matchingProviders.map(verify(_, times(1)).write(any[AssetDigest], aryEq(data)))
//              nonMatchingProviders.map(verify(_, never).write(any[AssetDigest], any[Array[Byte]]))
//              mediaType.toString == "application/vnd.blinkbox.books.mapping.update.v1+json"
//            }}
//            w.dismiss()
//        }
//      }
//    }
}
