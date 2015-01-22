package com.blinkbox.books.storageservice

import java.net.URL
import java.nio.file.{Paths, Files}

import akka.actor.{ActorSystem, ActorRefFactory}
import com.blinkbox.books.config.ApiConfig
import com.blinkbox.books.spray.v2
import com.blinkbox.books.storageservice.util.{Token, CommonMapping}
import com.blinkbox.books.test.MockitoSyrup
import org.mockito.Mockito._
import org.scalatest.{FlatSpec, Matchers}
import spray.http.{HttpEntity, BodyPart, MultipartFormData, StatusCodes}
import spray.routing.HttpService
import spray.testkit.ScalatestRouteTest

import scala.concurrent.Future

class QuarterMasterApiTest extends FlatSpec with ScalatestRouteTest with Matchers with MockitoSyrup with v2.JsonSupport {

  behavior of "The QuarterMasterApi"

  it should "produce the mapping files" in new TestFixture {
    val testMappings = Array(CommonMapping("test", "bbb:test:test", Map("test" -> "test")))
    when(quarterMasterService.mappings).thenReturn(testMappings)
    Get("/mappings") ~> routes ~> check {
      val response = responseAs[Array[CommonMapping]]
      assert(status == StatusCodes.OK)
      assert(response.length == 1)
      assert(response.head == testMappings.head)
    }
  }

  it should "get an existing resource if it exists" in new TestFixture {
    val token = Token("bbbmap:testfile:file:///some/location")
    val providerStatus = ProviderStatus(token, "label", Map("available" -> true))
    when(quarterMasterService.getTokenStatus(token)).thenReturn(Some(providerStatus))
    Get(s"/resources/${token.token}") ~> routes ~> check {
      assert(status == StatusCodes.OK)
      assert(responseAs[ProviderStatus] == providerStatus)
    }
  }

  it should "return 404 for a non existing resource" in new TestFixture {
    val token = Token("bbbmap:testfile:file:///some/location")
    when(quarterMasterService.getTokenStatus(token)).thenReturn(None)
    Get(s"/resources/${token.token}") ~> routes ~> check {
      assert(status == StatusCodes.NotFound)
    }
  }

  // Testing MultipartFormData is still unknown
  ignore should "return a valid ProviderStatus when uploading a binary file" in new TestFixture {
    val data = Files.readAllBytes(Paths.get(this.getClass.getResource("/sample_binary.jpg").getPath))
    val label = "testfile"
    val token = Token("bbbmap:testfile:file:///some/location")
    val providerStatus = ProviderStatus(token, "label", Map("available" -> true))
    when(quarterMasterService.storeAsset(label, data)).thenReturn(Future(Some(providerStatus)))
    Post(s"/resources", MultipartFormData(Map("label" -> BodyPart(label), "data" -> BodyPart(data)))) ~> routes ~> check {
      assert(status == StatusCodes.Accepted)
    }
  }

  class TestFixture extends HttpService {
    val apiConfig = mock[ApiConfig]
    val appConfig = mock[AppConfig]
    val quarterMasterService = mock[QuarterMasterService]
    when(apiConfig.localUrl).thenReturn(new URL("http://localhost"))
    when(appConfig.api).thenReturn(apiConfig)

    override implicit def actorRefFactory: ActorRefFactory = ActorSystem("quarter-master-test")

    val routes = QuarterMasterRoutes(appConfig, quarterMasterService, actorRefFactory).routes
  }
}