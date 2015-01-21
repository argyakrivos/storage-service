package com.blinkbox.books.storageservice

import com.blinkbox.books.storageservice.util.LocalStorageDao
import org.scalatest.mock.MockitoSugar
import org.scalatest.{Matchers, FlatSpec}
import spray.testkit.ScalatestRouteTest

class QuarterMasterServiceTest extends FlatSpec with ScalatestRouteTest with Matchers with MockitoSugar {

  "QuarterMasterService" should "calculate the md5 sum of a given string for generating file name" in new TestFixtures {

  }

  class TestFixtures {
    val mockedLocalStorageDao = mock[LocalStorageDao]

  }
}
