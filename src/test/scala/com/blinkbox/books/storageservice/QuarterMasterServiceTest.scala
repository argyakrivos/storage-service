package com.blinkbox.books.storageservice

import com.blinkbox.books.storageservice.util.{CommonMapping, LocalStorageDao}
import org.scalatest.mock.MockitoSugar
import org.scalatest.{FlatSpec, Matchers}
import spray.testkit.ScalatestRouteTest

import scala.concurrent.ExecutionContext.Implicits.global

class QuarterMasterServiceTest extends FlatSpec with ScalatestRouteTest with Matchers with MockitoSugar {

  "QuarterMasterService" should "calculate the md5 sum of a given string for generating file name" in {
    QuarterMasterService.createFileName("hello".toCharArray.map(_.toByte)) should be ("5d41402abc4b2a76b9719d911017c592")
  }

  class TestFixtures {
//    val mockedLocalStorageDao = mock[LocalStorageDao]
//    val appConfig = mock[AppConfig]
//    when(appConfig.mapping).thenReturn(Array(CommonMapping()))
//    val service = new QuarterMasterService(appConfig)
  }
}
