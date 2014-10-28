package com.blinkbox.books.storageservice

import akka.actor.{Props, ActorSystem}
import akka.io.IO
import akka.util.Timeout
import com.blinkbox.books.config.Configuration
import com.blinkbox.books.logging.Loggers
import com.blinkbox.books.spray.{Directives => CommonDirectives, HttpServer, v2}
import com.typesafe.scalalogging.slf4j.StrictLogging
import org.slf4j.LoggerFactory
import spray.http.StatusCodes._
import spray.http.{MediaTypes, StatusCodes}
import spray.routing._
import spray.util.LoggingContext
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.util.control.NonFatal
import scala.concurrent.duration._
import spray.can.Http

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
  val log = LoggerFactory.getLogger(classOf[QuarterMasterRoutes])

  val routes = monitor(log) {
    handleExceptions(exceptionHandler) {
      neverCache {
        rootPath(appConfig.hosturl) {
          quarterMasterRoute
        }
      }
    }
  }
}


object Main extends App with Configuration with Loggers with StrictLogging {

  logger.info("Starting readling list service")

  try {
    implicit val system = ActorSystem("storage-service", config)
    sys.addShutdownHook(system.shutdown())


    implicit val requestTimeout = Timeout(5.seconds)

    val appConfig = AppConfig( config, system)


    val httpActor = IO(Http)
    val webService = system.actorOf(Props(new QuarterMasterRoutes(QuarterMasterService(appConfig))), "storage-service")
    HttpServer(Http.Bind(webService, appConfig.host, appConfig.effectivePort))

  } catch {
    case e: Throwable =>
      logger.error("Error at startup, exiting", e)
      sys.exit(-1)
  }

  logger.info("Started reading list service")

}