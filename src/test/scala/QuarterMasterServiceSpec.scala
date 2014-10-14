


package com.blinkbox.books.storageservice

import com.blinkbox.books.config.Configuration
import org.scalacheck.Arbitrary.arbitrary
import org.scalacheck.Gen
import org.scalatest.prop.GeneratorDrivenPropertyChecks
import org.scalatest.{FlatSpecLike, Matchers}
import spray.testkit.ScalatestRouteTest

import scala.concurrent.Future



/**
 * Created by greg on 19/09/14.
 */




class QuarterMasterSpecification  extends   Configuration with FlatSpecLike with ScalatestRouteTest with Matchers with GeneratorDrivenPropertyChecks    {

 // probably could just use arbitrary[caseClass] , but this affords more control




  val templateGen = for {
    serviceName <- arbitrary[String]
    template <- arbitrary[String]
  } yield UrlTemplate(serviceName, template)

  val qms = new QuarterMasterService(AppConfig(config,system))

  val mappingGen = for {
    templateList <- Gen.listOf(templateGen)
    extractor <- arbitrary[String]
  }yield Mapping(MappingRaw(extractor, templateList))

"The quarterMasterService" should "update the mapping file " in {
  forAll(mappingGen, mappingGen) { (oldMapping: Mapping, newMapping:Mapping) =>
  val json: String = Mapping.toJson(newMapping)
  val f: Future[(Mapping, Any)] = qms._updateAndBroadcastMapping(json)
    //future.option.try.tuple
  f.value.get.get._1 == oldMapping
  }

}


}













