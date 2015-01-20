package com.blinkbox.books.storageservice

import java.lang.reflect.InvocationTargetException

import akka.actor._
import akka.util.Timeout
import com.blinkbox.books.config.Configuration
import com.blinkbox.books.logging.{DiagnosticExecutionContext, Loggers}
import com.blinkbox.books.spray.{Directives => CommonDirectives, HttpServer, HealthCheckHttpService, v2}
import com.blinkbox.books.storageservice.util.{DaoMappingUtils, Token}
import com.typesafe.scalalogging.StrictLogging
import org.json4s.MappingException
import org.json4s.jackson.Serialization
import spray.can.Http
import spray.http.StatusCodes._
import spray.http.Uri.Path
import spray.http._
import spray.httpx.unmarshalling._
import spray.routing._
import spray.util.NotImplementedException

import scala.concurrent.Future

case class QuarterMasterRoutes(config: AppConfig, qms: QuarterMasterService, actorRefFactory: ActorRefFactory) extends HttpService
with CommonDirectives with BasicUnmarshallers with v2.JsonSupport with StrictLogging {
  val mappingUri = "mappings"
  val refreshUri = "refresh"
  val resourcesUri = "resources"
  val appConfig = config
  val localUrl = appConfig.api.localUrl

  val mappingRoute = path(mappingUri) {
    get {
      complete(StatusCodes.OK, qms.mappings)
    }
  }

  val resourcesRoute = {
    implicit val formUnmarshaller = FormDataUnmarshallers.multipartFormDataUnmarshaller(strict = false)
    implicit def textUnmarshaller[T: Manifest]: Unmarshaller[T] =
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
          val data = new MultipartFormField("data", form.get("data")).as[Array[Byte]]
          val label= new MultipartFormField("label", form.get("label")).as[String]
          val storeResult = for {
            data_result <- data.right
            label_result <- label.right
          } yield  qms.storeAsset(label_result, data_result)

          storeResult match {
            case Right((token)) => complete(StatusCodes.Accepted)
            case Left(e) => complete(InternalServerError, e)
          }
        }
      }
    } ~
    path(resourcesUri/ Segment) { token =>
      get {
        complete(qms.getTokenStatus(Token(token)))
      }
    }
  }

  private def exceptionHandler = ExceptionHandler {
    case e: NotImplementedException =>
      logger.warn("Unhandled error, no Storage Providers Found", e)
      uncacheable(BadRequest, "code: UnknownLabel")
    case e: IllegalArgumentException =>
      logger.warn("Unhandled error:  Bad Request", e)
      uncacheable(BadRequest, "code: Bad Request")
  }

  val routes = {
    handleExceptions(exceptionHandler) {
      neverCache {
        rootPath(Path("/")) {
          mappingRoute ~ resourcesRoute
        }
      }
    }
  }
}

class WebService(appConfig: AppConfig, qms: QuarterMasterService) extends HttpServiceActor {
  implicit val executionContext = DiagnosticExecutionContext(actorRefFactory.dispatcher)
  val actorRefFac = actorRefFactory
  val healthService = new HealthCheckHttpService {
    override implicit def actorRefFactory: ActorContext = actorRefFac

    override val basePath = Path./
  }
  val routes = new QuarterMasterRoutes(appConfig, qms, actorRefFactory).routes

  override def receive: Actor.Receive = runRoute(routes ~ healthService.routes)
}

object Boot extends App with Configuration with Loggers with StrictLogging with DaoMappingUtils {
  override val appConfig = AppConfig(config)
  logger.info("Starting QuarterMaster StorageServer")
  
  implicit val system = ActorSystem("storage-service", config)
  implicit val executionContext = DiagnosticExecutionContext(system.dispatcher)
  implicit val timeout = Timeout(5000)
  val maps = mappings(appConfig.mapping.path)
  val daos = mappingsToDao(maps)
  val service = system.actorOf(Props(new WebService(appConfig, new QuarterMasterService(appConfig))))
  val localUrl = appConfig.api.localUrl
  HttpServer(Http.Bind(service, localUrl.getHost, port = localUrl.getPort))
}
