package com.blinkbox.books.storageservice

import com.blinkbox.books.config.Configuration
import com.blinkbox.books.json.DefaultFormats
import com.blinkbox.books.test.MatcherSugar.eql
import org.json4s.JsonDSL._
import org.json4s.jackson.JsonMethods._
import org.json4s.{FieldSerializer, JValue}
import org.mockito.AdditionalMatchers.aryEq
import org.mockito.Matchers.any
import org.mockito.Mockito.{atLeastOnce, never, reset, times, verify, when}
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
import spray.http.StatusCodes._
import spray.http._
import spray.testkit.ScalatestRouteTest
import spray.util.NotImplementedException
import scala.concurrent.Future
import scala.util.Random

class QuarterMasterSpecification extends Configuration with FlatSpecLike with ScalatestRouteTest
with Matchers with GeneratorDrivenPropertyChecks with ScalaFutures {
  implicit val formats = DefaultFormats + FieldSerializer[Mapping]() + FieldSerializer[UrlTemplate]()
  val initMapping: Mapping = Mapping("", List())

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

  val appConfig = AppConfig(config, MockitoSugar.mock[BlinkboxRabbitMqConfig], HealthServiceConfig(system), MockitoSugar.mock[StorageConfig])
  MappingHelper.loader = new MappingLoader {
    override def load(path: String): String = mappingJsonStr
    override def write(path: String, json: String): Unit = ()
  }
  val mockSender = MockitoSugar.mock[MessageSender]
  val mockStorageManager  = MockitoSugar.mock[StorageManager]
  val qms = new QuarterMasterService(appConfig, initMapping, mockSender, mockStorageManager)

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

  def getMockDelegate(delegateType: DelegateType, answer: Answer[Future[(DelegateType, Status)]]):StorageDelegate = {
    val mockDelegate = MockitoSugar.mock[StorageDelegate]
    when(mockDelegate.cleanUp(any[AssetToken])).thenReturn(Future.successful((delegateType, Status.failed)))
    when(mockDelegate.delegateType).thenReturn(delegateType)
    when(mockDelegate.write(any[AssetToken], any[Array[Byte]])).thenAnswer(answer)
    mockDelegate
  }

  def getFailingDelegate(delegateType: DelegateType, e: Exception, repo:StorageWorkerRepo) = {
    val mockDelegate = MockitoSugar.mock[StorageDelegate]
    when(mockDelegate.cleanUp(any[AssetToken])).thenReturn(Future.successful((delegateType, Status.failed)))
    when(mockDelegate.delegateType).thenReturn(delegateType)
    when(mockDelegate.write(any[AssetToken], any[Array[Byte]])).thenReturn(Future.failed(e))
    mockDelegate
  }

  val mockSuccessfulDelegateConfigGen = for {
    labels <- Gen.listOf(Gen.chooseNum(minlabel, maxlabel))
    delegateType = DelegateType("mockingDelegate" +System.nanoTime)
  } yield new DelegateConfig(getMockDelegate(delegateType, successfulWriteAnswer(delegateType)), labels.toSet)

  def mockSuccessfulDelegateConfigGenWithLabel(label: Int) =
  {
   val delegateType = DelegateType("mockingDelegate" +System.nanoTime)
   new DelegateConfig(getMockDelegate(delegateType, successfulWriteAnswer(delegateType)), Set(label))
 }

  def mockFailingDelegateConfigGen(repo:StorageWorkerRepo)  = for {
    labels <- Gen.listOf(Gen.chooseNum(minlabel, maxlabel))
    delegateType = DelegateType("mockingDelegate" +System.nanoTime)
  } yield new DelegateConfig(getFailingDelegate(delegateType, new IllegalArgumentException, repo), labels.toSet)

  val mockSuccessfulDelegateConfigSetGen = for {
    successfulDelegateConfigs <- Gen.listOf(mockSuccessfulDelegateConfigGen)
  } yield successfulDelegateConfigs.toSet

  def mockFailingDelegateSetGen(repo:StorageWorkerRepo) = for {
    failingWriters <- Gen.nonEmptyListOf(mockFailingDelegateConfigGen(repo))
  } yield failingWriters.toSet

  def mockFailingMixedDelegateSetGen(repo:StorageWorkerRepo) = for {
    successfulDelegateConfigs <- Gen.listOf(mockSuccessfulDelegateConfigGen)
    failingDelegateConfigs <- Gen.listOf(mockFailingDelegateConfigGen(repo))
  } yield Random.shuffle(failingDelegateConfigs.union(successfulDelegateConfigs))

  "The quarterMasterService" should "update the mapping file " in {
    forAll(mappingGen, mappingGen2) { (oldMapping, newMapping) =>
      (!newMapping.extract[Mapping].templates.isEmpty || true) ==> {
        val json = newMapping.toString
        val expected= compact(render(newMapping))
        val f = qms.updateAndBroadcastMapping(expected)
        whenReady(f)(_ == expected)
      }
    }
  }

  "The quarterMasterService" should "not update the mapping with bad json " in {
    forAll(mappingGen, alphaStr) { (oldMapping, json) =>
      qms.mapping = oldMapping
      val expected = MappingHelper.toJson(oldMapping)
      val f = qms.updateAndBroadcastMapping(json)
      whenReady(f)(_ shouldEqual expected)
    }
  }

  "The quarterMasterService" should "not  load bogus data " in {
    forAll(mappingGen, alphaStr) { (oldMapping, bogusMapping) =>
      MappingHelper.loader = new MappingLoader {
        override def load(path: String): String = bogusMapping
        override def write(path: String, json:String): Unit= ()
      }
      qms.mapping = oldMapping
      val expected =MappingHelper.toJson(oldMapping)
      val f = qms.loadMapping
      whenReady[String, Unit](f)((s) => s shouldEqual expected)
    }
  }

  "The quarterMasterService" should "  load good data " in {
    forAll(mappingGen, mappingGen2) { (oldMapping, loaded) =>
      val loadStr = compact(render(loaded))
      MappingHelper.loader = new MappingLoader {
        override def load(path: String): String = loadStr
        override def write(path: String, json:String): Unit= ()
      }
      qms.mapping = oldMapping
      val f = qms.loadMapping
      whenReady(f)((s) => {
        s shouldEqual loadStr
      })
    }
  }
  val minlabel = 0
  val maxlabel = 3
   def delegateConfiguredForLabel(label:Int,  delegateConfigs: Set[DelegateConfig]): Boolean = delegateConfigs.exists( _.labels.contains(label))

  "the quarterMaster" should "upload assets" in {
    forAll(mockSuccessfulDelegateConfigSetGen, arbitrary[Array[Byte]], arbitrary[Int]) {
      (mockDelegateConfigSet, data, label) => {
          val repo = new InMemoryRepo
          val storageManager = new StorageManager(repo,mockDelegateConfigSet.toSet)
          val newConfig = AppConfig(config, appConfig.rmq, appConfig.hsc, appConfig.sc)
          val mockSender = MockitoSugar.mock[MessageSender]
          val qms2 = new QuarterMasterService(newConfig, initMapping, mockSender, storageManager)
          val callAccepted = qms2.storeAsset(data, label)
          val f = callAccepted.flatMap[Map[DelegateType, Status]]((callFinished) => callFinished._2)
        if (!delegateConfiguredForLabel(label, mockDelegateConfigSet)) {
          whenReady(f.failed) {
            e => e shouldBe a [NotImplementedException]
          }
        } else
        if (data.size < 1) {
          whenReady(f.failed) {
            e => e shouldBe a [IllegalArgumentException]
          }
        }else
          whenReady(f)((s) => {
            val matchingDelegates = mockDelegateConfigSet.filter((dc) => dc.labels.contains(label)).map(_.delegate)
            val nonMatchingDelegates = mockDelegateConfigSet.filter((dc) => !dc.labels.contains(label)).map(_.delegate)
            val size = s.size
            val msize= matchingDelegates.size
            val assetToken= callAccepted.futureValue._1
            matchingDelegates.map(verify(_, times(1)).write(eql(assetToken), aryEq(data)))
            nonMatchingDelegates.map(verify(_, never).write(any[AssetToken], any[Array[Byte]]))
            size shouldBe msize
            size == msize
          })
        }
    }
  }

  "the quarterMaster" should "clean up failed assets" in {
        val repo = MockitoSugar.mock[StorageWorkerRepo]
    forAll(mockSuccessfulDelegateConfigSetGen, mockFailingDelegateSetGen(repo), arbitrary[Array[Byte]], arbitrary[Int]) {
      (successfulDelegateSet, mockFailingDelegateSet, data, label) => {
        reset(repo)
          when(repo.updateProgress(any[JobId], any[Long], any[DateTime], any[Long])).thenReturn(Future.successful(()))
          when(repo.removeProgress(any[JobId])).thenReturn(Future.successful(()))
        when(repo.getStatus(any[JobId])).thenReturn(Future(Status.notFound))
        val randomSuccessAndFailingWriterConfigs = Random.shuffle(successfulDelegateSet.union(mockFailingDelegateSet))
        val storageManager = new StorageManager(repo,randomSuccessAndFailingWriterConfigs.toSet)
        val newConfig = AppConfig(config, appConfig.rmq, appConfig.hsc, appConfig.sc)
        val qms2 = new QuarterMasterService(newConfig, initMapping, MockitoSugar.mock[MessageSender], storageManager)
        val callAccepted = qms2.storeAsset(data, label)
        val f = callAccepted.flatMap(_._2)
        if (!delegateConfiguredForLabel(label, randomSuccessAndFailingWriterConfigs)) {
          whenReady(f.failed) {
            e => e shouldBe a [NotImplementedException]
          }
        }else
        if (data.size < 1) {
          whenReady(f.failed) {
            e => e shouldBe a [IllegalArgumentException]
          }
        } else
        whenReady(f)((s) => {
          val allDelegates = randomSuccessAndFailingWriterConfigs.map(_.delegate).toSet
          val matchingDelegates = storageManager.label2Delegates(label)
          val failingNonMatching = allDelegates.diff(matchingDelegates)
          val assetToken = callAccepted.futureValue._1
          matchingDelegates.map(verify(_, times(1)).write(eql(assetToken), aryEq(data)))
          failingNonMatching.map(verify(_, never).write(any[AssetToken], any[Array[Byte]]))
          matchingDelegates.map(verify(_, times(1)).cleanUp(eql(assetToken)))
          failingNonMatching.map(verify(_, never).cleanUp(any[AssetToken]))
          true
        })
      }
    }
  }

  it should "connect to the correct mappings" in  {
    val router = new QuarterMasterRoutes(qms, system)
    def routes = router.routes
    Get("/mappings") ~> routes ~> check {
      assert(status == OK )
      mediaType.toString == "application/vnd.blinkbox.books.v2+json"
    }
  }

  it should "connect reload the mapping path" in  {
    val router = new QuarterMasterRoutes(qms,system)
    def routes = router.routes
    Put("/mappings/refresh") ~> routes ~> check {
      assert(status == OK )
      mediaType.toString == "application/vnd.blinkbox.books.v2+json"
    }
  }

  it should "save an artifact" in {
    val label = 2
    forAll(Gen.listOf(mockSuccessfulDelegateConfigGenWithLabel(label)), Gen.nonEmptyListOf(arbitrary[Byte]) ) {
      (mockDelegateConfigList, datalist) => {
          val mockDelegateConfigSet = mockDelegateConfigList.toSet
          val data = datalist.toArray
          val repo = new InMemoryRepo
          val storageManager = new StorageManager(repo,mockDelegateConfigSet)
          val newConfig = AppConfig(config, appConfig.rmq, appConfig.hsc, appConfig.sc)
          val mockSender = MockitoSugar.mock[MessageSender]
          val service = new QuarterMasterService(newConfig, initMapping, mockSender, storageManager)
          val router = new QuarterMasterRoutes(service,system)
          def routes = router.routes
          val compressible  = true
          val binary = true
          val contentType = ContentType(MediaType.custom("application", "epub+zip", compressible, binary, Seq[String]("epub"), Map.empty))
          Post("/resources",
            MultipartFormData(
              Map(
                "label" -> BodyPart(HttpEntity(ContentTypes.`text/plain`, label.toString)),
                "data" -> BodyPart(HttpEntity(contentType, HttpData(data)))
              ))
          ) ~> routes ~> check {
            assert(status == Accepted)
            val matchingDelegates:Set[StorageDelegate] = mockDelegateConfigSet.filter( _.labels.contains(label)).map(_.delegate)
            val nonMatchingDelegates = mockDelegateConfigSet.filter( !_.labels.contains(label)).map(_.delegate)
            Thread.sleep(40)
              matchingDelegates.map(verify(_, atLeastOnce).write(any[AssetToken], aryEq(data)))
              nonMatchingDelegates.map(verify(_,never).write(any[AssetToken], any[Array[Byte]]))
              mediaType.toString == "application/vnd.blinkbox.books.v2+json"
          }
        }
      }
    }
}
