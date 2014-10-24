package com.blinkbox.books.storageservice

import java.io._
import akka.actor.ActorRef
import com.blinkbox.books.config.Configuration
import com.blinkbox.books.json.DefaultFormats
import com.blinkbox.books.messaging._
import com.blinkbox.books.spray.{v2, Directives => CommonDirectives}
import org.json4s.FieldSerializer
import org.json4s.jackson.JsonMethods
import org.json4s.jackson.Serialization._
import spray.http.StatusCodes._
import spray.http.{MediaTypes, StatusCodes}
import spray.routing._
import spray.util.LoggingContext

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.concurrent.{Await, Future}
import scala.io.Source
import scala.util.control.NonFatal

trait MappingLoader {
  def load(path: String): String
}

class FileMappingLoader extends MappingLoader {
  override def load(path: String): String =
    Source.fromFile(path).mkString("")
}

object Mapping extends JsonMethods with v2.JsonSupport {
  implicit val formats = DefaultFormats + FieldSerializer[Mapping]() + FieldSerializer[UrlTemplate]()
  val EXTRACTOR_NAME = "extractor"
  val TEMPLATES_NAME = "templates"
  var loader: MappingLoader = new FileMappingLoader

  def fromJsonStr(jsonString: String): Mapping = {
    val m = read[Option[MappingRaw]](jsonString).map(new Mapping(_))
    m.getOrElse(throw new IllegalArgumentException(s"cant parse jsonString: $jsonString"))
  }

  def toJson(m: Mapping): String = {
    import org.json4s.JsonDSL._
    val mr = m.m
    val json =
      ("extractor" -> mr.extractor) ~
        ("templates" ->
          mr.templates.map {
            ((urlt) => ("serviceName" -> urlt.serviceName) ~ ("template" -> urlt.template))
          })
    compact(json)
    //    write[MappingRaw](m.m)
  }

  def load(path: String): Future[Mapping] = Future {
    loader.load(path)
  }.map(fromJsonStr(_: String))

  implicit object Mapping extends JsonEventBody[Mapping] {
    val jsonMediaType = MediaType("application/vnd.blinkbox.books.ingestion.quartermaster.v2+json")
  }
}


case class MappingRaw(extractor: String, templates: List[UrlTemplate])
//TODO rename to mapping model
case class Mapping(m: MappingRaw) extends JsonMethods with v2.JsonSupport with Configuration {
  implicit val formats = DefaultFormats + FieldSerializer[Mapping]() + FieldSerializer[UrlTemplate]()
  implicit val timeout = AppConfig.timeout

  def store(mappingPath: String): Future[Unit] = Future {
    val fw = new FileWriter(mappingPath)
    fw.write(Mapping.toJson(this))
    fw.close()
  }

  def broadcastUpdate(qsender: ActorRef, eventHeader: EventHeader): Future[Any] = {
    import akka.pattern.ask
    qsender ? Event.json[Mapping](eventHeader, this)
  }

  val jsonMediaType: MediaType = MediaType("application/vnd.blinkbox.books.ingestion.quartermaster.v2+json")
}


trait RestRoutes extends HttpService {
  def getAll: Route
}


case class QuarterMasterService(appConfig: AppConfig) {
  def cleanUp(assetToken: AssetToken, label: Int): Future[Map[DelegateType, Status]] = {
    storageWorker.cleanUp(assetToken, label).map((_.toMap))
  }

  val storageWorker = new QuarterMasterStorageWorker(appConfig.swc)

  def storeAsset(bytes: Array[Byte], label: Int): Future[(AssetToken, Future[Map[DelegateType, Status]])] = Future {
    val assetToken = genToken(bytes)
    val f: Future[Map[DelegateType, Status]] = storageWorker.storeAsset(assetToken, bytes, label)
    (assetToken, f)
  }

  var mapping: Mapping = Await.result(Mapping.load(appConfig.mappingpath), 1000 millis)

  def getStatus(token: AssetToken): Future[Map[DelegateType, Status]] = {
    storageWorker.getStatus(token)
  }

  def genToken(data: Array[Byte]): AssetToken = new AssetToken(data.hashCode.toString)

  def _updateAndBroadcastMapping(mappingStr: String): Future[String] = {
    (for {
      mapping <- Future {
        Mapping.fromJsonStr(mappingStr)
      }
      _ <- mapping.store(appConfig.mappingpath)
      broadcaststatus <- mapping.broadcastUpdate(appConfig.rmq.qSender, appConfig.eventHeader)
    } yield (mapping, broadcaststatus)).recover[(Mapping, Any)] {
      case _ => (this.mapping, false)
    }.map[String](t => Mapping.toJson(t._1))
  }

  def loadMapping(): Future[String] =
    Mapping.load(appConfig.mappingpath).map((loaded: Mapping) => {
      mapping = loaded
      mapping
    }).recover[Mapping] {
      case _ => mapping
    }.map((Mapping.toJson(_)))

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
          type StorageRequestReturnType = (AssetToken, Future[Map[DelegateType, Status]])
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

  //should return 202
  val updateMappingRoute =
    post {
      parameters('mappingJson) {
        (mappingString: String) => {
          complete(StatusCodes.Accepted, qms._updateAndBroadcastMapping(mappingString))
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







