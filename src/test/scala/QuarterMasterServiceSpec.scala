


package com.blinkbox.books.storageservice

import com.blinkbox.books.config.Configuration
import com.blinkbox.books.json.DefaultFormats
import common.UrlTemplate
import org.json4s.JsonDSL._
import org.json4s.jackson.JsonMethods._
import org.json4s.{FieldSerializer, JValue}
import org.scalacheck.Arbitrary.arbitrary
import org.scalacheck.Gen
import org.scalacheck.Gen.alphaStr
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.prop.GeneratorDrivenPropertyChecks
import org.scalatest.{FlatSpecLike, Matchers}
import spray.testkit.ScalatestRouteTest
import worker._
import org.scalacheck.Prop.{BooleanOperators}
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
  val qmss = new QuarterMasterStorageService(appConfig)

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
      val f =qms.loadMapping
      whenReady[String, Unit](f)((s: String) =>{ s  shouldEqual loadStr})

    }
  }


}
















