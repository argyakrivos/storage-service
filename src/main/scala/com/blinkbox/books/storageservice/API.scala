package com.blinkbox.books.storageservice

import akka.actor._
import akka.util.Timeout
import com.blinkbox.books.config.Configuration
import com.blinkbox.books.logging.{DiagnosticExecutionContext, Loggers}
import com.blinkbox.books.spray.Directives._
import com.blinkbox.books.spray.{HealthCheckHttpService, HttpServer, v2}
import com.blinkbox.books.spray.v2.Implicits.throwableMarshaller
import com.blinkbox.books.storageservice.util.{StoreMappingUtils, Token}
import com.typesafe.scalalogging.StrictLogging
import spray.can.Http
import spray.http.StatusCodes._
import spray.http.Uri.Path
import spray.http._
import spray.httpx.unmarshalling._
import spray.routing._
import spray.routing.directives.DebuggingDirectives
import spray.util.NotImplementedException

import scala.concurrent.ExecutionContext

case class QuarterMasterRoutes(appConfig: AppConfig, qms: QuarterMasterService, actorRefFactory: ActorRefFactory)(implicit context: ExecutionContext) extends HttpService
  with v2.JsonSupport with StrictLogging {
  val mappingUri = "mappings"
  val resourcesUri = "resources"
  val localUrl = appConfig.api.localUrl

  val mappingRoute = path(mappingUri) {
    get {
      complete(StatusCodes.OK, qms.mappings)
    }
  }

  val setResourcesRoute = {
    implicit val formUnmarshaller = FormDataUnmarshallers.multipartFormDataUnmarshaller(strict = false)

    path(resourcesUri) {
      post {
        entity(as[MultipartFormData]) { form =>
          val extractor = FormFieldExtractor(form)
          val data = extractor.field("data").as[Array[Byte]]
          val label = extractor.field("label").as[String]

          val storeResult = for {
            d <- data.right
            l <- label.right
          } yield
            qms.storeAsset(l, d)
          storeResult match {
            case Right(result) =>
              onSuccess(result)(complete(Accepted, _))
            case Left(e) =>
              e match {
                case ContentExpected => complete(BadRequest, "Please provide content for the fields label and data")
                case _ => complete(InternalServerError)
              }
          }
        }
      }
    }
  }
  
  val getResourcesRoute = {
    path(resourcesUri / Rest) { token =>
      get {
        complete(qms.getTokenStatus(Token(token)))
      }
    }
  }

  private def exceptionHandler = ExceptionHandler {
    case e: NotImplementedException =>
      failWith(new IllegalRequestException(BadRequest, "Unhandled error, no Storage Providers Found"))
    case e: IllegalArgumentException =>
      failWith(new IllegalRequestException(BadRequest, e.getMessage))
  }

  val routes = monitor(logger, throwableMarshaller){
    handleExceptions(exceptionHandler) {
      neverCache {
        DebuggingDirectives.logRequest("get-user") {
          rootPath(Path(localUrl.getPath)) {
            mappingRoute ~ setResourcesRoute ~ getResourcesRoute
          }
        }
      }
    }
  }
}

class WebService(appConfig: AppConfig, qms: QuarterMasterService) extends HttpServiceActor {
  implicit val executionContext = DiagnosticExecutionContext(actorRefFactory.dispatcher)
  val healthService = new HealthCheckHttpService {
    override implicit val actorRefFactory = WebService.this.actorRefFactory
    override val basePath = Path./
  }
  val routes = new QuarterMasterRoutes(appConfig, qms, actorRefFactory).routes

  override def receive: Actor.Receive = runRoute(routes ~ healthService.routes)
}

object Boot extends App with Configuration with Loggers with StrictLogging {
  val appConfig = AppConfig(config)
  logger.info("Starting QuarterMaster StorageServer")
  
  implicit val system = ActorSystem("storage-service", config)
  implicit val executionContext = DiagnosticExecutionContext(system.dispatcher)
  implicit val timeout = Timeout(appConfig.api.timeout)
  val service = system.actorOf(Props(new WebService(appConfig, new QuarterMasterService(appConfig))))
  val localUrl = appConfig.api.localUrl
  HttpServer(Http.Bind(service, localUrl.getHost, port = localUrl.getPort))
}
