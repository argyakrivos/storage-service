package com.blinkbox.books.storageservice

import java.util.UUID

import com.blinkbox.books.config.Configuration
import com.blinkbox.books.json.DefaultFormats
import org.json4s.JsonDSL._
import org.json4s.jackson.JsonMethods._
import org.json4s.{FieldSerializer, JValue}
import org.mockito.Matchers._
import org.mockito.Mockito
import org.mockito.invocation.InvocationOnMock
import org.mockito.stubbing.Answer
import org.scalacheck.Arbitrary.arbitrary
import org.scalacheck.Gen
import org.scalacheck.Gen.alphaStr
import org.scalacheck.Prop.BooleanOperators
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.mock.MockitoSugar
import org.scalatest.prop.GeneratorDrivenPropertyChecks
import org.scalatest.{FlatSpecLike, Matchers}
import shapeless.~>
import spray.http.StatusCodes._
import spray.http._
import spray.testkit.ScalatestRouteTest

import scala.concurrent.Future
import scala.util.Random

class QuarterMasterSpecification extends Configuration with FlatSpecLike with ScalatestRouteTest
with Matchers with GeneratorDrivenPropertyChecks with ScalaFutures {
  implicit val formats = DefaultFormats + FieldSerializer[Mapping]() + FieldSerializer[UrlTemplate]()
  val initMapping:Mapping = Mapping("", List())
  import scala.collection.JavaConverters._
  config.entrySet().asScala.map(println(_))
  val templateGen = for {
    serviceName <- arbitrary[String]
    template <- arbitrary[String]
  } yield UrlTemplate(serviceName, template)
  val mappingJsonStr = """{"extractor":"^.*/(?P<filename>$.*)\\.(?P<extenstion>.{2,3})\\?",
      "templates":[{
      "serviceName":"azure-a",
      "template":"http://azureservices.com/blinkbox/\\g<filename>.\\g<extenstion>"}]}"""

 // val appConfig: AppConfig = AppConfig(config, system)

  val appConfig = AppConfig(config, MockitoSugar.mock[RabbitMQConfig], HealthServiceConfig(system), MockitoSugar.mock[StorageConfig], MockitoSugar.mock[StorageWorkerConfig])
  MappingHelper.loader = new MappingLoader {
    override def load(path: String): String = mappingJsonStr
    override def write(path: String, json:String): Unit= ()
  }
  val qms = new QuarterMasterService(appConfig, initMapping)


  val templateGen2: Gen[JValue] = for {
    serviceName <- arbitrary[String]
    template <- arbitrary[String]
  } yield ("serviceName" -> serviceName) ~ ("template" -> template)

  val mappingGen2 = for {
    extractor <- arbitrary[String]
    templateList <- Gen.listOf(templateGen2)
  } yield ("extractor" -> extractor) ~ ("templates" -> templateList)


  val mappingGen = for {
    templateList <- Gen.listOf(templateGen)
    extractor <- arbitrary[String]
  } yield Mapping(extractor, templateList)


  "The quarterMasterService" should "update the mapping file " in {
    forAll(mappingGen, mappingGen2) { (oldMapping: Mapping, newMapping: JValue) =>
      //ok this property will always apply but is left as a reference on how to filter properties
      (!newMapping.extract[Mapping].templates.isEmpty || true) ==> {
        val json: String = newMapping.toString
        val expected: String = compact(render(newMapping))
        val f: Future[String] = qms.updateAndBroadcastMapping(expected)
        whenReady(f)(_ == expected)
      }
    }
  }

  "The quarterMasterService" should " not update the mapping with bad json " in {
    forAll(mappingGen, alphaStr) { (oldMapping: Mapping, json: String) =>
      qms.mapping = oldMapping
      val expected = MappingHelper.toJson(oldMapping)
      val f: Future[String] = qms.updateAndBroadcastMapping(json)
      whenReady(f)(_ shouldEqual expected)
    }
  }

  "The quarterMasterService" should "not  load bogus data " in {
    forAll(mappingGen, alphaStr) { (oldMapping: Mapping, bogusMapping: String) =>
      MappingHelper.loader = new MappingLoader {
        override def load(path: String): String = bogusMapping
        override def write(path: String, json:String): Unit= ()
      }
      qms.mapping = oldMapping
      val expected =MappingHelper.toJson(oldMapping)
      val f = qms.loadMapping
      whenReady[String, Unit](f)((s: String) => s shouldEqual expected)
    }
  }


  "The quarterMasterService" should "  load good data " in {
    forAll(mappingGen, mappingGen2) { (oldMapping: Mapping, loaded: JValue) =>
      val loadStr = compact(render(loaded))
      MappingHelper.loader = new MappingLoader {
        override def load(path: String): String = loadStr
        override def write(path: String, json:String): Unit= ()
      }
      qms.mapping = oldMapping
      val f = qms.loadMapping
      whenReady(f)((s: String) => {
        s shouldEqual loadStr
      })
    }
  }

  def successfulWriteAnswer(delegateType: DelegateType): Answer[Future[(DelegateType, Status)]] = new Answer[Future[(DelegateType, Status)]] {
    override def answer(invocation: InvocationOnMock): Future[(DelegateType, Status)] = {
      invocation.getArguments.head match {
        case assetTokenArg: AssetToken => Future {
          (delegateType, new Status(DateTime.now, true))
        }
      }
    }
  }

  def failingWriteAnswer(e: Throwable): Answer[Future[(DelegateType, Status)]] = new Answer[Future[(DelegateType, Status)]] {
    override def answer(invocation: InvocationOnMock): Future[(DelegateType, Status)] = Future.failed(e)
  }

  def getMockDelegate(delegateType: DelegateType, answer: Answer[Future[(DelegateType, Status)]]) = {
    val mockDelegate = MockitoSugar.mock[StorageDelegate]
    Mockito.when(mockDelegate.cleanUp(any[AssetToken])).thenReturn(Future.successful({
      (delegateType, Status.neverStatus)
    }))
    Mockito.when(mockDelegate.delegateType).thenReturn(delegateType)
    Mockito.when(mockDelegate.write(any(), any())).thenAnswer(answer)
    mockDelegate
  }

  def getFailingDelegate(delegateType: DelegateType, e: Exception) = {
    val mockDelegate = MockitoSugar.mock[StorageDelegate]
    Mockito.when(mockDelegate.cleanUp(any[AssetToken])).thenReturn(Future.successful({
      (delegateType, Status.neverStatus)
    }))
    Mockito.when(mockDelegate.write(any(), any())).thenReturn(Future.failed(e))
    mockDelegate
  }

  val minlabel = 0
  val maxlabel = 3

  val mockSuccessfulDelegateConfigGen = for {
    labels <- Gen.listOf(Gen.chooseNum(minlabel, maxlabel))
    delegateType = DelegateType("mockingDelegate" + UUID.randomUUID().toString)
  } yield new DelegateConfig(getMockDelegate(delegateType, successfulWriteAnswer(delegateType)), labels.toSet)

  val mockFailingDelegateConfigGen = for {
    labels <- Gen.listOf(Gen.chooseNum(minlabel, maxlabel))
    delegateType = DelegateType("mockingDelegate" + UUID.randomUUID().toString)
  } yield new DelegateConfig(getFailingDelegate(delegateType, new IllegalArgumentException), labels.toSet)

  val mockSuccessfulDelegateConfigSetGen = for {
    successfulDelegateConfigs <- Gen.listOf(mockSuccessfulDelegateConfigGen)
  } yield successfulDelegateConfigs.toSet

  val mockFailingDelegateSetGen = for {
    failingWriters <- Gen.nonEmptyListOf(mockFailingDelegateConfigGen)
  } yield failingWriters.toSet

  val mockFailingMixedDelegateSetGen = for {
    successfulDelegateConfigs <- Gen.listOf(mockSuccessfulDelegateConfigGen)
    failingDelegateConfigs <- Gen.listOf(mockFailingDelegateConfigGen)
  } yield Random.shuffle(failingDelegateConfigs.union(successfulDelegateConfigs))

  "the quarterMaster" should "upload assets" in {
    forAll(mockSuccessfulDelegateConfigSetGen, arbitrary[Array[Byte]], arbitrary[Int]) {
      (mockDelegateConfigSet: Set[DelegateConfig], data: Array[Byte], label: Int) => {
        val mockSwConfig: StorageWorkerConfig = new StorageWorkerConfig(mockDelegateConfigSet.toSet)
        val newConfig = AppConfig(config, appConfig.rmq, appConfig.hsc, appConfig.sc, mockSwConfig)
        val qms2 = new QuarterMasterService(newConfig, initMapping)

        //extract the work future from the result
        val f = qms2.storeAsset(data, label).flatMap[Map[DelegateType, Status]]((callFinished: (AssetToken, Future[Map[DelegateType, Status]])) => callFinished._2)
        whenReady(f)((s: Map[DelegateType, Status]) => {
          val matchingDelegates = mockDelegateConfigSet.filter((dc: DelegateConfig) => dc.labels.contains(label)).map(_.delegate)
          val nonMatchingDelegates = mockDelegateConfigSet.filter((dc: DelegateConfig) => !dc.labels.contains(label)).map(_.delegate)
          val size: Int = s.size
          val msize: Int = matchingDelegates.size
          matchingDelegates.map((mockDelegate: StorageDelegate) => Mockito.verify(mockDelegate, Mockito.times(1)).write(any[AssetToken], any[Array[Byte]]))
          nonMatchingDelegates.map((mockDelegate: StorageDelegate) => Mockito.verify(mockDelegate, Mockito.times(0)).write(any[AssetToken], any[Array[Byte]]))
          size shouldBe msize
          size == msize
        })
      }
    }
  }

  "the quarterMaster" should "clean up failed assets" in {
    forAll(mockSuccessfulDelegateConfigSetGen, mockFailingDelegateSetGen, arbitrary[Array[Byte]], arbitrary[Int]) {
      (successfulDelegateSet: Set[DelegateConfig], mockFailingDelegateSet: Set[DelegateConfig], data: Array[Byte], label: Int) => {
        val randomSuccessAndFailingWriterConfigs = Random.shuffle(successfulDelegateSet.union(mockFailingDelegateSet))
        val mockSwConfig: StorageWorkerConfig = new StorageWorkerConfig(randomSuccessAndFailingWriterConfigs.toSet)
        val newConfig = AppConfig(config, appConfig.rmq, appConfig.hsc, appConfig.sc, mockSwConfig)
        val qms2 = new QuarterMasterService(newConfig, initMapping)
        val f: Future[Map[DelegateType, Status]] = qms2.storeAsset(data, label).flatMap((callFinished: (AssetToken, Future[Map[DelegateType, Status]])) => callFinished._2)
        whenReady(f)((s: Map[DelegateType, Status]) => {
          val matchingDelegates = mockFailingDelegateSet.filter((dc: DelegateConfig) => dc.labels.contains(label)).map(_.delegate)
          val nonMatchingDelegates = mockFailingDelegateSet.filter((dc: DelegateConfig) => !dc.labels.contains(label)).map(_.delegate)
          val size: Int = s.size
          val msize: Int = matchingDelegates.size
          matchingDelegates.map((mockDelegate: StorageDelegate) => Mockito.verify(mockDelegate, Mockito.times(1)).write(any[AssetToken], any[Array[Byte]]))
          nonMatchingDelegates.map((mockDelegate: StorageDelegate) => Mockito.verify(mockDelegate, Mockito.times(0)).write(any[AssetToken], any[Array[Byte]]))
          matchingDelegates.map((mockDelegate: StorageDelegate) => Mockito.verify(mockDelegate, Mockito.times(1)).cleanUp(any[AssetToken]))
          nonMatchingDelegates.map((mockDelegate: StorageDelegate) => Mockito.verify(mockDelegate, Mockito.times(0)).cleanUp(any[AssetToken]))
          true
        })
      }
    }
  }
  it should "connect to the correct mappings" in  {
          val router = new QuarterMasterRoutes(qms)
          def routes = router.routes
          Get("/mappings") ~> routes ~> check {
            assert(status == OK )
            mediaType.toString == "application/vnd.blinkbox.books.v2+json"
        }
      }

  it should "save an artifact" in {
    forAll(mockSuccessfulDelegateConfigSetGen, arbitrary[Array[Byte]], arbitrary[Int]) {
      (mockDelegateConfigSet: Set[DelegateConfig], data: Array[Byte], label: Int) => {
        val mockLoader = MockitoSugar.mock[MappingLoader]
        val mockSwConfig: StorageWorkerConfig = new StorageWorkerConfig(mockDelegateConfigSet.toSet)
        val newConfig = AppConfig(config, appConfig.rmq, appConfig.hsc, appConfig.sc, mockSwConfig)
        val qms2 = new QuarterMasterService(newConfig, initMapping)

        val router = new QuarterMasterRoutes(qms2)
        def routes = router.routes
        val contentType: ContentType = ContentType(MediaType.custom("application", "epub+zip", true, true, Seq[String]("epub"), Map.empty))
        Post("/resources",
          MultipartFormData(
            Map(
              "label" -> BodyPart(HttpEntity(ContentTypes.`text/plain`,label.toString)),
              "data" -> BodyPart(HttpEntity(contentType, HttpData(new Array[Byte](3))))
              ))
          ) ~> routes ~> check {
          assert(status == Accepted)
          val delegates:Set[StorageDelegate] = mockDelegateConfigSet.map(_.delegate)
          val matchingDelegates = mockDelegateConfigSet.filter((dc: DelegateConfig) => dc.labels.contains(label)).map(_.delegate)
          val nonMatchingDelegates = mockDelegateConfigSet.filter((dc: DelegateConfig) => !dc.labels.contains(label)).map(_.delegate)
          Thread.sleep(5)
          matchingDelegates.map((mockDelegate: StorageDelegate) => Mockito.verify(mockDelegate, Mockito.times(1)).write(any[AssetToken], any[Array[Byte]]))
          nonMatchingDelegates.map((mockDelegate: StorageDelegate) => Mockito.verify(mockDelegate, Mockito.times(0)).write(any[AssetToken], any[Array[Byte]]))
          if (!delegates.isEmpty) { delegates.map( Mockito.reset(_))}
          mediaType.toString == "application/vnd.blinkbox.books.v2+json"
        }
      }
    }
  }

}
