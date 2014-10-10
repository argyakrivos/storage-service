


package com.blinkbox.books.storageservice

import org.scalacheck.Arbitrary.arbitrary
import org.scalacheck.Gen
import org.scalatest.prop.GeneratorDrivenPropertyChecks
import org.scalatest.{FlatSpecLike, Matchers}

import scala.concurrent.Future
import scalaz.effect.IO


/**
 * Created by greg on 19/09/14.
 */




class QuarterMasterSpecification  extends FlatSpecLike with Matchers with GeneratorDrivenPropertyChecks    {

 // probably could just use arbitrary[caseClass] , but this affords more control



  val runtimeConfig = QuarterMasterRuntimeDeps(null)
  val templateGen = for {
    serviceName <- arbitrary[String]
    template <- arbitrary[String]
  } yield UrlTemplate(serviceName, template)

  val qms = new QuarterMasterService

  val mappingGen = for {
    templateList <- Gen.listOf(templateGen)
    extractor <- arbitrary[String]
  }yield Mapping(extractor, templateList)

"The quarterMasterService" should "update the mapping file " in {
  forAll(mappingGen, mappingGen) { (oldMapping: Mapping, newMapping:Mapping) =>
  val tuple: (Mapping, IO[Future[Any]]) = qms.updateAndBroadcastMapping(runtimeConfig)(newMapping.toJson).run(oldMapping)
  tuple._1 == oldMapping

  }

}


}













