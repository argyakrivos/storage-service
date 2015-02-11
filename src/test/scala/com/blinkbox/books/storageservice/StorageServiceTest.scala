package com.blinkbox.books.storageservice

import com.blinkbox.books.storageservice.util.{CommonMapping, LocalStorageStore}
import org.scalatest.mock.MockitoSugar
import org.scalatest.{FlatSpec, Matchers}
import spray.testkit.ScalatestRouteTest

class StorageServiceTest extends FlatSpec with ScalatestRouteTest with Matchers with MockitoSugar {

  "QuarterMasterService" should "calculate the md5 sum of a given string for generating file name" in {
    StorageService.createFileName("hello".toCharArray.map(_.toByte)) should be ("5d41402abc4b2a76b9719d911017c592")
  }
}
