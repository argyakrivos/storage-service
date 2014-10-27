package com.blinkbox.books.storageservice

import com.blinkbox.books.spray.v2
import spray.http.StatusCodes._
import spray.http.{MediaTypes, StatusCodes}
import spray.routing._
import spray.util.LoggingContext
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.util.control.NonFatal
import com.blinkbox.books.spray.{Directives => CommonDirectives}

trait RestRoutes extends HttpService {
  def getAll: Route
}
class QuarterMasterRoutes(qms: QuarterMasterService) extends HttpServiceActor
with RestRoutes with CommonDirectives with v2.JsonSupport {

  implicit val timeout = AppConfig.timeout
  val appConfig = qms.appConfig
  val mappingRoute = path(appConfig.mappingUri) {
    get {
      complete(qms.mapping)
    }
  }

  override def getAll: Route = {
    get {
      pathEndOrSingleSlash {
        uncacheable(InternalServerError, None)
      }
    }
  }

  val storeAssetRoute = {
    path("upload") {
      post {
        formFields('data.as[Array[Byte]], 'label.as[Int]) { (data, label) =>
          val f: Future[AssetToken] = (qms.storeAsset(data, label)).map[AssetToken]((_._1))
          complete(StatusCodes.Accepted, f)
        }
      }
    }
  }

  val assetUploadStatus = path(appConfig.statusMappingUri) {
    get {
      parameters('token.as[String]).as(AssetToken) {
        (assetToken: AssetToken) =>
          respondWithMediaType(MediaTypes.`application/json`) {
            complete(StatusCodes.OK, qms.getStatus(assetToken))
          }
      }
    }
  }

  val updateMappingRoute =
    post {
      parameters('mappingJson) {
        (mappingString: String) => {
          complete(StatusCodes.Accepted, qms.updateAndBroadcastMapping(mappingString))
        }
      }
    }

  val reloadMappingRoute = path(appConfig.refreshMappingUri) {
    get {
      respondWithMediaType(MediaTypes.`application/json`) {
        complete(StatusCodes.OK, qms.loadMapping)
      }
    }
  }

  val quarterMasterRoute = mappingRoute ~ reloadMappingRoute ~ updateMappingRoute ~ appConfig.hsc.healthService.routes ~ storeAssetRoute
  def receive = runRoute(quarterMasterRoute)

  private def exceptionHandler(implicit log: LoggingContext) = ExceptionHandler {
    case NonFatal(e) =>
      log.error(e, "Unhandled error")
      uncacheable(InternalServerError, None)
  }
}