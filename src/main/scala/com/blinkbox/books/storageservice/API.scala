package com.blinkbox.books.storageservice
import java.lang.reflect.InvocationTargetException
import akka.actor.{ Actor, ActorRefFactory, ActorSystem, Props }
import akka.util.Timeout
import com.blinkbox.books.config.Configuration
import com.blinkbox.books.json.DefaultFormats
import com.blinkbox.books.logging.DiagnosticExecutionContext
import com.blinkbox.books.spray.{ HealthCheckHttpService, HttpServer, v2, Directives => CommonDirectives }
import com.typesafe.scalalogging.StrictLogging
import org.json4s.`package`.MappingException
import org.json4s.jackson.Serialization
import org.json4s.FieldSerializer
import org.slf4j.LoggerFactory
import spray.can.Http
import spray.http.StatusCodes._
import com.blinkbox.books.spray._
import spray.http.Uri.Path
import spray.http._
import spray.httpx.unmarshalling._
import spray.routing._
import spray.util.{ LoggingContext, NotImplementedException }
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.util.Right
import scala.util.control.NonFatal
import com.typesafe.scalalogging.StrictLogging

case class QuarterMasterRoutes(qms: QuarterMasterService, actorRefFactory: ActorRefFactory) extends HttpService
  with CommonDirectives with BasicUnmarshallers with v2.JsonSupport with StrictLogging {
    val mappingUri = "mappings"
    val refreshUri = "refresh"
    val resourcesUri = "resources"
    val appConfig = qms.appConfig
    val mappingRoute = path(mappingUri) {

    implicit val timeout = AppConfig.timeout
      get {
        complete(StatusCodes.OK, qms.mappingHelper.toJson(qms.storageManager.mapping.get))
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
            } yield qms.storeAsset(data, label).map[AssetDigest](_._1)
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
          assetDigest => complete(StatusCodes.OK, qms.getStatus(assetDigest))
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

    val reloadMappingRoute =
      path(mappingUri / refreshUri) {
        put {
          complete(StatusCodes.OK, qms.loadMapping)
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
            mappingRoute ~ reloadMappingRoute ~ updateMappingRoute ~ storeAssetRoute
          }
        }
      }
    }
}

class WebService(config: AppConfig, qms: QuarterMasterService) extends HttpServiceActor {
  implicit val executionContext = DiagnosticExecutionContext(actorRefFactory.dispatcher)
  val healthService = new HealthCheckHttpService {
    override implicit def actorRefFactory = WebService.this.actorRefFactory
    override val basePath = Path./
  }
  val routes = new QuarterMasterRoutes(qms, actorRefFactory)
  override def receive: Actor.Receive = runRoute(routes.routes ~ healthService.routes)
}

object Boot extends App with Configuration with StrictLogging {
  val initMapping: Mapping = Mapping(List())
  logger.info("Starting quartermaster storage service")
  try {
    implicit val system = ActorSystem("storage-service", config)
    sys.addShutdownHook(system.shutdown())
    implicit val requestTimeout = Timeout(5.seconds)
    val appConfig = AppConfig(config, system)
    val messageSender = new MessageSender(appConfig, system)
    val repo = new InMemoryRepo
    val localStorageDao = LocalStorageDao(LocalStorageConfig(appConfig.storage.head))
    val provider = StorageProvider(repo, localStorageDao)
    val storageManager = StorageManager(repo, initMapping, Set(provider))
    val service = QuarterMasterService(appConfig, messageSender, storageManager, MappingHelper(new FileMappingLoader))
    val webService = system.actorOf(Props(classOf[WebService], appConfig, service), "storage-service")
    val localUrl = appConfig.api.localUrl
    HttpServer(Http.Bind(webService, localUrl.getHost, port = localUrl.effectivePort))
  } catch {
    case NonFatal(e) =>
      logger.error("Error at startup, exiting", e)
      sys.exit(-1)
  }
  logger.info("Started quartermaster storage service")
}
