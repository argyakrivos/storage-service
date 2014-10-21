


package com.blinkbox.books.storageservice

import com.blinkbox.books.config.Configuration
import com.blinkbox.books.json.DefaultFormats
import common.{DelegateType, Status, AssetToken, UrlTemplate}
import org.json4s.JsonDSL._
import org.json4s.jackson.JsonMethods._
import org.json4s.{FieldSerializer, JValue}
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
import spray.http.DateTime
import spray.testkit.ScalatestRouteTest
import worker._

import scala.concurrent.Future




/**
 * Created by greg on 19/09/14.
 */




class QuarterMasterSpecification  extends   Configuration   with FlatSpecLike with ScalatestRouteTest
with Matchers with GeneratorDrivenPropertyChecks  with ScalaFutures {
  implicit val formats = DefaultFormats + FieldSerializer[Mapping]() + FieldSerializer[UrlTemplate]()
  // probably could just use arbitrary[caseClass] , but this affords more control


  val templateGen = for {
    serviceName <- arbitrary[String]
    template <- arbitrary[String]
  } yield UrlTemplate(serviceName, template)
  val mappingJsonStr = """{"extractor":"^.*/(?P<filename>$.*)\\.(?P<extenstion>.{2,3})\\?",
      "templates":[{
      "serviceName":"azure-a",
      "template":"http://azureservices.com/blinkbox/\\g<filename>.\\g<extenstion>"}]}"""

  val appConfig: AppConfig = AppConfig(config, system)
  Mapping.loader = new MappingLoader {
    override def load(path: String): String = mappingJsonStr
  }
  val qms = new QuarterMasterService(appConfig)


  val templateGen2:Gen[JValue] = for {
    serviceName <- arbitrary[String]
    template <- arbitrary[String]
  } yield   ("serviceName" -> serviceName) ~  ("template" -> template)

  val mappingGen2 = for {
    extractor <- arbitrary[String]
    templateList <- Gen.listOf(templateGen2)
  } yield  ("extractor" -> extractor) ~ ("templates" -> templateList)


  val mappingGen = for {
    templateList <- Gen.listOf(templateGen)
    extractor <- arbitrary[String]
  } yield Mapping(MappingRaw(extractor, templateList))

  "The quarterMasterService" should "update the mapping file " in {

    forAll(mappingGen, mappingGen2) { (oldMapping: Mapping, newMapping: JValue) =>
      //ok this property will always apply but is left as a reference on how to filter properties
      ( !newMapping.extract[MappingRaw].templates.isEmpty || true ) ==> {
      val json: String = newMapping.toString
      val expected: String = compact(render(newMapping))
      val f: Future[String] = qms._updateAndBroadcastMapping(expected)
      whenReady[String, Boolean](f)((actual:String) =>actual  == expected )
    }
    }
  }

  "The quarterMasterService"  should " not update the mapping with bad json " in {
    forAll(mappingGen, alphaStr) { (oldMapping: Mapping, bogusMapping: String) =>
      val json: String = bogusMapping
      qms.mapping = oldMapping
      val expected = Mapping.toJson(oldMapping)
      val f: Future[String] = qms._updateAndBroadcastMapping(json)
      whenReady[String, Unit](f)((s:String) => s shouldEqual expected)

    }
  }

    "The quarterMasterService" should  "not  load bogus data " in {
      forAll(mappingGen, alphaStr) { (oldMapping: Mapping, bogusMapping: String) =>

        Mapping.loader = new MappingLoader {
          override def load(path: String): String = bogusMapping
        }
        qms.mapping = oldMapping
        val expected = Mapping.toJson(oldMapping)
        val f =qms.loadMapping

        whenReady[String, Unit](f)((s: String) => s  shouldEqual expected)

      }
  }


  "The quarterMasterService" should  "  load good data " in {
    forAll(mappingGen, mappingGen2) { (oldMapping: Mapping, loaded: JValue) =>

      val loadStr = compact(render(loaded))
      Mapping.loader = new MappingLoader {
        override def load(path: String): String = loadStr
      }
      qms.mapping = oldMapping
      val f = qms.loadMapping
      whenReady[String, Unit](f)((s: String) => {
        s shouldEqual loadStr
      })

    }

  }



  "the quarterMaster " should "upload an asset" in {
    import org.mockito.Matchers._
    def getmockDelegate(name:String) = {
      val mockDelegate = MockitoSugar.mock[StorageDelegate]
      val delegateType = new DelegateType(name)
      Mockito.when(mockDelegate.delegateType).thenReturn(delegateType)
      Mockito.when(mockDelegate.write(any(),any())).thenAnswer( new Answer[(DelegateType,Status)]{
        override def answer(invocation: InvocationOnMock): (DelegateType,Status) ={
          println(s" $name writing")
          invocation.getArguments.head match {
          case assetTokenArg:AssetToken => (delegateType, new Status(DateTime.now, true))
        }}

      })
      mockDelegate
    }
    forAll  { (data:Array[Byte], label:Int ) =>

      val mockDelegate1 = getmockDelegate("mockDelegate1")
      val mockDelegate2 = getmockDelegate("mockDelegate2")
      val mockDelegate3 = getmockDelegate("mockDelegate3")

println("*********")
      val mockSwConfig: StorageWorkerConfig = new StorageWorkerConfig(Set(new DelegateConfig(mockDelegate1, Set(1)), new DelegateConfig(mockDelegate2, Set(1,2)), new DelegateConfig(mockDelegate3, Set(3))))

      val newConfig = AppConfig(appConfig.rmq, appConfig.hsc, appConfig.sc, mockSwConfig)
      val qms2 = new QuarterMasterService(newConfig)
  //    Thread.sleep(1000)
      val f = qms2.storeAsset(data, 1).flatMap[Map[DelegateType, Status]]((callFinished:(AssetToken, Future[Map[DelegateType,Status]])) => callFinished._2)
      whenReady[Map[DelegateType,Status], Unit](f)((s: Map[DelegateType,Status]) => {
        //this is bollocks, it returns a future but theres no guarantee that the workers have recieved the request

        s.size should equal(2)
        Mockito.verify(mockDelegate1).write(any[AssetToken],any[Array[Byte]])
        Mockito.verify(mockDelegate2).write(any[AssetToken],any[Array[Byte]])
      //  Mockito.verifyZeroInteractions(mockDelegate3)

      })
//      println(mockSwConfig.delegates)
   }



    }


}
















