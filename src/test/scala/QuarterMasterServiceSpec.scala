package com.blinkbox.books.marvin

import com.blinkbox.books.json.DefaultFormats
import org.json4s.jackson.JsonMethods
import org.junit.runner.RunWith
import org.scalatest.FlatSpecLike
import org.scalatest.junit.JUnitRunner
import spray.httpx.Json4sJacksonSupport

import scala.language.{implicitConversions, postfixOps}

/**
 * Created by greg on 19/09/14.
 */


@RunWith(classOf[JUnitRunner])
class QuarterMasterServiceSpec extends FlatSpecLike with Json4sJacksonSupport with JsonMethods {
  implicit val json4sJacksonFormats = DefaultFormats






}
