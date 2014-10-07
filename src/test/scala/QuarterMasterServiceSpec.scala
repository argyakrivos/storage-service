


package com.blinkbox.books.storageservice
import com.blinkbox.books.storageservice.{QuarterMasterRoutes, QuarterMasterService}
import spray.testkit.ScalatestRouteTest
import spray.http.StatusCodes._
import com.blinkbox.books.json.DefaultFormats
import org.json4s.jackson.JsonMethods
import org.scalatest.{FlatSpecLike, Matchers}
import spray.httpx.Json4sJacksonSupport

import spray.testkit.Specs2RouteTest
import spray.routing.HttpService




/**
 * Created by greg on 19/09/14.
 */




class QuarterMasterServiceSpec(qms:QuarterMasterService) extends QuarterMasterRoutes(qms) with FlatSpecLike
with Json4sJacksonSupport with ScalatestRouteTest with  JsonMethods  with Matchers {
  override def actorRefFactory = runtimeConfig.arf
  implicit val json4sJacksonFormats = DefaultFormats
  it should "return the mapping file "  in {
    Get("/mapping/update") ~> quarterMasterRoute ~> check {
      status === OK
    }
  }



}







