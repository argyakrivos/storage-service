package com.blinkbox.books.storageservice

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
  val initMapping: Mapping = Mapping("", List())

  val labelGen = for {
    labelNum:Int <- Gen.chooseNum(minlabel, maxlabel)
  } yield Label(labelNum.toString)

  val templateGen = for {
    serviceName <- arbitrary[String]
    template <- arbitrary[String]
  } yield UrlTemplate(serviceName, template)

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
  } yield Mapping(extractor, templateList)

  val mockSuccessfulProviderConfigGen = for {
    labels <- Gen.listOf(labelGen)
    providerType = ProviderType("mockingProvider" +System.nanoTime)
  } yield new ProviderConfig(getSuccessfulProvider(providerType, successfulWriteAnswer(providerType)), labels.toSet)

  val mockSuccessfulProviderConfigSetGen = for {
    successfulProviderConfigs <- Gen.listOf(mockSuccessfulProviderConfigGen)
  } yield successfulProviderConfigs.toSet

  implicit val formats = DefaultFormats + FieldSerializer[Mapping]() + FieldSerializer[UrlTemplate]()

  val mappingJsonStr = """{"extractor":"^.*/(?P<filename>$.*)\\.(?P<extenstion>.{2,3})\\?",
      "templates":[{
      "serviceName":"azure-a",
      "template":"http://azureservices.com/blinkbox/\\g<filename>.\\g<extenstion>"}]}"""

  val appConfig = AppConfig(config, MockitoSugar.mock[BlinkboxRabbitMqConfig], MockitoSugar.mock[StorageConfig])
  MappingHelper.loader = new MappingLoader {
    override def load(path: String): String = mappingJsonStr
    override def write(path: String, json: String): Unit = ()
  }

  def failingWriteAnswer(e: Throwable): Answer[Future[(ProviderType, Status)]] = new Answer[Future[(ProviderType, Status)]] {
    override def answer(invocation: InvocationOnMock): Future[(ProviderType, Status)] = Future.failed(e)
  }

  def successfulWriteAnswer(providerType: ProviderType): Answer[Future[(ProviderType, Status)]] = new Answer[Future[(ProviderType, Status)]] {
    override def answer(invocation: InvocationOnMock): Future[(ProviderType, Status)] = {
      invocation.getArguments.head match {
        case assetTokenArg: AssetDigest => Future {
          (providerType, Status.finished)
        }
      }
    }
  }

  def getSuccessfulProvider(providerType: ProviderType, answer: Answer[Future[(ProviderType, Status)]]):StorageProvider = {
    val mockStorageDao = MockitoSugar.mock[StorageDao]
    val mockRepo = MockitoSugar.mock[StorageProviderRepo]
    val provider =new StorageProvider(mockRepo, providerType, mockStorageDao)
    when(mockRepo.getStatus(any[JobId])).thenReturn(Future.successful(Status.notFound))
    when(mockRepo.updateProgress(any[JobId],any[Long], any[DateTime], any[Long])).thenReturn(Future.successful(()))
    when(mockRepo.removeProgress(any[JobId])).thenReturn(Future.successful(()))
    when(mockStorageDao.write(any[AssetDigest], any[Array[Byte]])).thenAnswer(new Answer[Future[Unit]] {
      override def answer(invocation: InvocationOnMock): Future[Unit] = {Future.successful(())}
    })
    provider
  }

  def getFailingProvider(providerType: ProviderType, e: Exception) = {
    val mockStorageDao = MockitoSugar.mock[StorageDao]
    val mockRepo = MockitoSugar.mock[StorageProviderRepo]
    val provider =new StorageProvider(mockRepo, providerType, mockStorageDao)
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

  def mockSuccessfulProviderConfigGenWithLabel(label: Label) = {
   val providerType = ProviderType("mockingProvider" +System.nanoTime)
   new ProviderConfig(getSuccessfulProvider(providerType, successfulWriteAnswer(providerType)), Set(label))
  }

  "The quarterMasterService" should "update the mapping file " in {
    forAll(mappingGen, mappingGen2) { (oldMapping, newMapping) =>
      (!newMapping.extract[Mapping].templates.isEmpty || true) ==> {
        val json = newMapping.toString
        val expected= compact(render(newMapping))
        val mockSender = MockitoSugar.mock[MessageSender]
        val mockStorageManager  = MockitoSugar.mock[StorageManager]
        val qms = new QuarterMasterService(appConfig, initMapping, mockSender, mockStorageManager)
        val f = qms.updateAndBroadcastMapping(expected)
        whenReady(f)(_ == expected)
      }
    }
  }

  "The quarterMasterService" should "not update the mapping with bad json " in {
    forAll(mappingGen, alphaStr) { (oldMapping, json) =>
      val mockSender = MockitoSugar.mock[MessageSender]
      val mockStorageManager  = MockitoSugar.mock[StorageManager]
      val qms = new QuarterMasterService(appConfig, initMapping, mockSender, mockStorageManager)
      qms.mapping.set(oldMapping)
      val expected = MappingHelper.toJson(oldMapping)
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
      val mockSender = MockitoSugar.mock[MessageSender]
      val mockStorageManager  = MockitoSugar.mock[StorageManager]
      val qms = new QuarterMasterService(appConfig, initMapping, mockSender, mockStorageManager)
      qms.mapping.set(oldMapping)
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
      val mockSender = MockitoSugar.mock[MessageSender]
      val mockStorageManager  = MockitoSugar.mock[StorageManager]
      val qms = new QuarterMasterService(appConfig, initMapping, mockSender, mockStorageManager)
      qms.mapping.set(oldMapping)
      val f = qms.loadMapping
      whenReady(f)((s) => {
        s shouldEqual loadStr
      })
    }
  }

  val  mockFailingProviderConfigGen  = for {
    labels <- Gen.listOf(labelGen)
    providerType = ProviderType("mockingProvider" +System.nanoTime)
  } yield new ProviderConfig(getFailingProvider(providerType, new IllegalStateException), labels.toSet)

  "the quarterMaster" should "clean up failed assets" in {
    val label = Label("2:2")
    val labeledFailingProviderConfigGen = for {
      labels <- Gen.listOf(labelGen)
      providerType = ProviderType("mockingProvider" + System.nanoTime)
    } yield new ProviderConfig(getFailingProvider(providerType, new IllegalStateException), labels.toSet.+(label))
    forAll(Gen.listOf(mockSuccessfulProviderConfigGen), Gen.nonEmptyListOf(labeledFailingProviderConfigGen),
      Gen.nonEmptyListOf(arbitrary[Byte])
    ) {
      (successfulProviderSet, mockFailingProviderSet, dataList) => {
        mockFailingProviderSet
        val w = new Waiter
        val data = dataList.toArray
        val repo = MockitoSugar.mock[StorageProviderRepo]
        when(repo.updateProgress(any[JobId], any[Long], any[DateTime], any[Long])).thenReturn(Future.successful(()))
        when(repo.removeProgress(any[JobId])).thenReturn(Future.successful(()))
        when(repo.getStatus(any[JobId])).thenReturn(Future(Status.notFound))
        val randomSuccessAndFailingWriterConfigs = Random.shuffle(successfulProviderSet.toSet.union(mockFailingProviderSet.toSet))
        val storageManager = new StorageManager(repo, randomSuccessAndFailingWriterConfigs.toSet)
        val newConfig = AppConfig(config, appConfig.rmq, appConfig.sc)
        val qms2 = new QuarterMasterService(newConfig, initMapping, MockitoSugar.mock[MessageSender], storageManager)
        val callAccepted = qms2.storeAsset(data, label)
        w{
        val f = callAccepted.flatMap(_._2)
        whenReady(f)((s) =>  {
          val assetToken = callAccepted.futureValue._1
          val matchingSuccessfulDaos = successfulProviderSet.filter(_.labels.contains(label)).map(_.provider.dao)
          val matchingFailingDaos = mockFailingProviderSet.filter(_.labels.contains(label)).map(_.provider.dao)
          matchingSuccessfulDaos.map(verify(_, times(1)).write(eql(assetToken), aryEq(data)))
          matchingSuccessfulDaos.map(verify(_, never).cleanUp(any[AssetDigest]))
          matchingFailingDaos.map(verify(_, times(1)).write(eql(assetToken), aryEq(data)))
          matchingFailingDaos.map(verify(_, times(1)).cleanUp(any[AssetDigest]))
          w.dismiss()
        })}
        w.await()
      }
    }
  }

  it should "connect to the correct mappings" in  {
    val mockSender = MockitoSugar.mock[MessageSender]
    val mockStorageManager  = MockitoSugar.mock[StorageManager]
    val qms = new QuarterMasterService(appConfig, initMapping, mockSender, mockStorageManager)
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
    val qms = new QuarterMasterService(appConfig, initMapping, mockSender, mockStorageManager)
    val router = new QuarterMasterRoutes(qms,createActorSystem())
    def routes = router.routes
    Put("/mappings/refresh") ~> routes ~> check {
      assert(status == OK )
      mediaType.toString == "application/vnd.blinkbox.books.mapping.update.v1+json"
    }
  }

  it should "save an artifact" in {
    val label = Label("2")
    forAll(Gen.nonEmptyListOf(mockSuccessfulProviderConfigGenWithLabel(label)), Gen.nonEmptyListOf(arbitrary[Byte]) ) {
      (mockProviderConfigList, datalist) => {
          val w = new Waiter
          val mockProviderConfigSet = mockProviderConfigList.toSet
          val data = datalist.toArray
          val repo = new InMemoryRepo
          val storageManager = new StorageManager(repo,mockProviderConfigSet)
          val newConfig = AppConfig(config, appConfig.rmq, appConfig.sc)
          val mockSender = MockitoSugar.mock[MessageSender]
          val service = new QuarterMasterService(newConfig, initMapping, mockSender, storageManager)
          val router = new QuarterMasterRoutes(service,createActorSystem())
          def routes = router.routes
          val compressible  = true
          val binary = true
          import scala.concurrent.duration._
          val testtimeout = 3.seconds
          val contentType = ContentType(MediaType.custom("application", "epub+zip", compressible, binary, Seq[String]("epub"), Map.empty))
          Post("/resources",
            MultipartFormData(
              Map(
                "label" -> BodyPart(HttpEntity(ContentTypes.`text/plain`, label.label)),
                "data" -> BodyPart(HttpEntity(contentType, HttpData(data)))
              ))
          ) ~> routes ~> check {
            w{
              assert(status == Accepted)
              val matchingProviders: Set[StorageProvider] = mockProviderConfigSet.filter(_.labels.contains(label)).map(_.provider)
              val nonMatchingProviders = mockProviderConfigSet.filter(!_.labels.contains(label)).map(_.provider)
              matchingProviders.map(verify(_, times(1)).write(any[AssetDigest], aryEq(data)))
              nonMatchingProviders.map(verify(_, never).write(any[AssetDigest], any[Array[Byte]]))
              mediaType.toString == "application/vnd.blinkbox.books.mapping.update.v1+json"
            }}
            w.dismiss()
        }
      }
    }
}
