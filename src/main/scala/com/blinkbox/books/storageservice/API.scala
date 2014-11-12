package com.blinkbox.books.storageservice

import java.lang.reflect.InvocationTargetException

import akka.actor.{ActorRefFactory, ActorSystem, Props}
import akka.util.Timeout
import com.blinkbox.books.config.Configuration
import com.blinkbox.books.json.DefaultFormats
import com.blinkbox.books.spray.{Directives => CommonDirectives, HealthCheckHttpService, HttpServer, v2}
import com.typesafe.scalalogging.slf4j.{StrictLogging}
import org.json4s.{FieldSerializer, MappingException}
import org.json4s.jackson.Serialization
import org.slf4j.LoggerFactory
import spray.can.Http
import spray.http.StatusCodes._
import spray.http.Uri.Path
import spray.http._
import spray.httpx.unmarshalling._
import spray.routing._
import spray.util.{LoggingContext, NotImplementedException}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.util.Right
import scala.util.control.NonFatal

case class QuarterMasterRoutes(qms: QuarterMasterService, actorRefFactory: ActorRefFactory) extends HttpService
with CommonDirectives with BasicUnmarshallers with v2.JsonSupport{
  val log = LoggerFactory.getLogger(classOf[QuarterMasterRoutes])
  val mappingUri = "mappings"
  val refreshUri = "refresh"
  val resourcesUri = "resources"
  implicit val timeout = AppConfig.timeout
  val appConfig = qms.appConfig

  val mappingRoute = path(mappingUri) {
    get {
      complete(StatusCodes.OK,MappingHelper.toJson(qms.mapping.get))
    }
  }

  val storeAssetRoute = {
    implicit val formUnmarshaller = FormDataUnmarshallers.MultipartFormDataUnmarshaller
    implicit def textUnmarshaller[T: Manifest] =
      Unmarshaller[T](MediaTypes.`text/plain`) {
        case x: HttpEntity.NonEmpty =>
          try Serialization.read[T](x.asString(defaultCharset = HttpCharsets.`UTF-8`))
          catch {
            case MappingException("unknown error", ite: InvocationTargetException) => throw ite.getCause
          }
      }
    path(resourcesUri) {
      post {
        entity(as[MultipartFormData]) { (form) =>
          val dataRight = new MultipartFormField("data", form.get("data")).as[Array[Byte]]
          val labelRight = new MultipartFormField("label", form.get("label")).as[String]
          val result = for {
            data <- dataRight.right
            label <- labelRight.right
          } yield qms.storeAsset(data, Label(label)).map[AssetDigest](_._1)
          result match {
            case Right(result) => complete(StatusCodes.Accepted, result)
            case _ => complete(StatusCodes.InternalServerError)
          }
        }
      }
    }
  }

  val assetUploadStatus =
    get {
      implicit val formats = DefaultFormats + FieldSerializer[AssetDigest]()
      path(resourcesUri / Segment).as(AssetDigest) {
        assetToken => complete(StatusCodes.OK, qms.getStatus(assetToken))
      }
    }

  val updateMappingRoute =
    path(mappingUri) {
      post {
        parameters('mappingJson) {
          (mappingString) => complete(StatusCodes.Accepted, qms.updateAndBroadcastMapping(mappingString))
        }
      }
    }

  val reloadMappingRoute = path(mappingUri / refreshUri) {
    put {
      complete(StatusCodes.OK, qms.loadMapping)
    }
  }

  private def exceptionHandler(implicit log: LoggingContext) = ExceptionHandler {
    case e: NotImplementedException => log.error(e, "Unhandled error")
      uncacheable(NotImplemented, "code: UnknownLabel")
    case e: IllegalArgumentException => log.error(e, "Unhandled error")
      uncacheable(BadRequest, "code: UnknownLabel")
  }
  val routes = {
    handleExceptions(exceptionHandler) {
      neverCache {
        rootPath(appConfig.root) {
          mappingRoute ~ reloadMappingRoute ~ updateMappingRoute ~ storeAssetRoute
        }
      }
    }
  }
}

case class HealthService(arf: ActorRefFactory) {
  val healthService =
    new HealthCheckHttpService {
      override implicit def actorRefFactory = arf
      override val basePath = Path("/")
    }
}

class WebService(config: AppConfig, qms: QuarterMasterService) extends HttpServiceActor {
  implicit val executionContext = actorRefFactory.dispatcher
  val hsc = HealthService(actorRefFactory)
  val routes = new QuarterMasterRoutes(qms,actorRefFactory)
  override def receive: Receive = runRoute(routes.routes ~ hsc.healthService.routes )
}

object Boot extends App with Configuration with StrictLogging  {
  logger.info("Starting quartermaster storage service")
  try {
    implicit val system = ActorSystem("storage-service", config)
    sys.addShutdownHook(system.shutdown())
    implicit val requestTimeout = Timeout(5.seconds)
    val appConfig = AppConfig(config, system)
    val webService = system.actorOf(Props(classOf[WebService], appConfig), "storage-service")
    HttpServer(Http.Bind(webService, interface = appConfig.host, port = appConfig.effectivePort))
  } catch {
    case NonFatal(e) =>
      logger.error("Error at startup, exiting", e)
      sys.exit(-1)
  }
  logger.info("Started quartermaster storage service")
}