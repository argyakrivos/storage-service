
import java.io._

import akka.actor.{ActorRef, ActorRefFactory}
import com.blinkbox.books.json.DefaultFormats
import com.blinkbox.books.logging.DiagnosticExecutionContext
import com.blinkbox.books.messaging.{Event, EventHeader, JsonEventBody}
import com.blinkbox.books.spray.v1.Version1JsonSupport
import com.blinkbox.books.spray.{v2, Directives => CommonDirectives}
import org.json4s.JsonAST._
import org.json4s.jackson.JsonMethods
import org.json4s.{FieldSerializer, StringInput}
import spray.http.StatusCodes._
import spray.httpx.Json4sSupport
import spray.routing._
import spray.util.LoggingContext

import scala.concurrent.Future
import scala.io.Source
import scala.util.control.NonFatal
import scalaz.effect.IO

case class UserId(id:String)



case class UrlTemplate(serviceName:String, template:String)

case class Mapping (extractor: String, templates: List[UrlTemplate])  extends JsonMethods with Json4sSupport      {
  implicit val json4sFormats = DefaultFormats
  implicit val formats = DefaultFormats



  def store(mappingPath:String): IO[Unit] =
    IO {
      val fw = new FileWriter(mappingPath)
      fw.write(toJson(this))
      fw.close()
    }

  def toJson = (compact _) compose (asJValue[Mapping] _)

  def broadcastUpdate(qsender: ActorRef, eventHeader:EventHeader): IO[Future[Any]] =  IO {
    import akka.pattern.ask
    val eventBody = JsonEventBody(toJson(this))
    qsender ? Event(eventHeader, eventBody)
  }}



object Mapping extends JsonMethods with Json4sSupport  with Version1JsonSupport {

  val json4sFormats = DefaultFormats + FieldSerializer[Mapping]()
  val EXTRACTOR_NAME = "extractor"
  val TEMPLATES_NAME = "templates"

  def fromJsonStr(jsonString :String):Option[Mapping]  =
    parseOpt(StringInput(jsonString))  map   (fromJValue[Mapping](_:JValue))



  def load(path: String): IO[Option[Mapping]] = IO {
    val jsonString = Source.fromFile(path).mkString("")
    fromJsonStr(jsonString)
  }
}

trait RestRoutes extends HttpService {
  def getAll: Route
}





class QuarterMasterApi(config: QuarterMasterConfig) (implicit val actorRefFactory: ActorRefFactory)
  extends RestRoutes with CommonDirectives with v2.JsonSupport {

//throw an exception if it doesnt exist
  var mapping: Mapping = Mapping.load(config.mappingpath).unsafePerformIO().get


  implicit val executionContext = DiagnosticExecutionContext(actorRefFactory.dispatcher)

  val mappingRoute = path(config.mappingUri) {
    get {
      complete(mapping)
    }
  }

  override def getAll: Route = {
    get {
      pathEndOrSingleSlash {
        uncacheable(InternalServerError, None)
      }
    }
  }

  //should return 200
  val updateMappingRoute =

      (qs:ActorRef) =>
      post {
        parameters('mappingJson) {
          (mappingStr: String) => {
            val isNewMapping = Mapping.fromJsonStr(mappingStr)
            mapping = isNewMapping.getOrElse(mapping)
            mapping.store(config.mappingpath)
            val f: Future[Any] = mapping.broadcastUpdate(qs, config.eventHeader).unsafePerformIO()
            complete(f)
          }
        }
      }



  val reloadMappingRoute =
    path(config.refreshMappingUri) {
      get {
        mapping = Mapping.load(config.mappingpath).unsafePerformIO().get
        complete(mapping.toJson)
      }
    }

  val receiveReader =for{
    hs <- config.healthService
    umr <- updateMappingRoute
  } yield runRoute(mappingRoute ~ reloadMappingRoute ~ umr ~ hs.routes)

  val receive = Nil


  private def exceptionHandler(implicit log: LoggingContext) = ExceptionHandler {
    case NonFatal(e) =>
      log.error(e, "Unhandled error")
      uncacheable(InternalServerError, None)
  }

}

















