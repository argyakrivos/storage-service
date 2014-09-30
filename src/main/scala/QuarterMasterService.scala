
import java.io._

import akka.actor.{ActorRef, ActorRefFactory}
import com.blinkbox.books.config.Configuration
import com.blinkbox.books.json.DefaultFormats
import com.blinkbox.books.logging.DiagnosticExecutionContext
import com.blinkbox.books.messaging._
import com.blinkbox.books.spray.{v2, Directives => CommonDirectives}
import org.json4s.FieldSerializer
import org.json4s.jackson.JsonMethods
import org.json4s.jackson.Serialization.{read, write}
import spray.http.StatusCodes._
import spray.routing._
import spray.util.LoggingContext

import scala.concurrent.Future
import scala.io.Source
import scala.util.control.NonFatal
import scalaz.effect.IO



object Mapping extends JsonMethods with v2.JsonSupport with Configuration  {
  val appConfig = new QuarterMasterConfig(config)
  implicit val formats =  DefaultFormats + FieldSerializer[Mapping]() + FieldSerializer[UrlTemplate]()

  val EXTRACTOR_NAME = "extractor"
  val TEMPLATES_NAME = "templates"


  //  def fromJsonStr(jsonString :String):Option[Mapping]  = for{
  //   json <- JsonMethods.parseOpt(jsonString, formats.wantsBigDecimal)
  //   maybeMapping <- json.extract\Opt(formats)
  //  } yield(maybeMapping)

  def fromJsonStr(jsonString :String):Option[Mapping] = read(jsonString)

  def load(path: String): IO[Option[Mapping]] = IO {
    val jsonString = Source.fromFile(path).mkString("")
    fromJsonStr(jsonString)
  }

  implicit object Mapping extends JsonEventBody[Mapping] {
    val jsonMediaType = MediaType("application/vnd.blinkbox.books.actions.email.send.v2+json")
    def unapply(body: EventBody): Option[( String, List[UrlTemplate])] = None
  }

}

case class UserId(id:String)

case class UrlTemplate(serviceName:String, template:String)

case class Mapping (extractor: String, templates: List[UrlTemplate])  extends JsonMethods  with v2.JsonSupport    {

  implicit val formats =  DefaultFormats + FieldSerializer[Mapping]() + FieldSerializer[UrlTemplate]()

  implicit val timeout = Mapping.appConfig.timeout

  def store(mappingPath:String): IO[Unit] =
    IO {
      val fw = new FileWriter(mappingPath)
      fw.write(toJson)
      fw.close()
    }

  def toJson:String = write(this)





  def broadcastUpdate(qsender: ActorRef, eventHeader:EventHeader): IO[Future[Any]] =  IO {
    import akka.pattern.ask

    qsender ? Event.json[Mapping](eventHeader, this)
  }

   val jsonMediaType: MediaType = MediaType("application/quatermaster+json")



}





trait RestRoutes extends HttpService {
  def getAll: Route
}







class QuarterMasterApi(config: QuarterMasterConfig) 
  extends HttpServiceActor with RestRoutes with CommonDirectives with v2.JsonSupport  {

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


  val qSender = Mapping.appConfig.qSender(actorRefFactory)
  //should return 200
  val updateMappingRoute =
      post {
        parameters('mappingJson) {
          (mappingStr: String) => {
            val isNewMapping = Mapping.fromJsonStr(mappingStr)
            mapping = isNewMapping.getOrElse(mapping)
            mapping.store(config.mappingpath)
            val f: Future[Any] = mapping.broadcastUpdate(qSender, config.eventHeader).unsafePerformIO()
            complete(f)
          }
        }
      }




  val reloadMappingRoute = path(config.refreshMappingUri) {
      get {
        mapping = Mapping.load(config.mappingpath).unsafePerformIO().get
        complete(mapping.toJson)
      }
    }


 val healthService = Mapping.appConfig.healthService(actorRefFactory)
 def receive = runRoute(mappingRoute ~ reloadMappingRoute ~ updateMappingRoute ~ healthService.routes)


  private def exceptionHandler(implicit log: LoggingContext) = ExceptionHandler {
    case NonFatal(e) =>
      log.error(e, "Unhandled error")
      uncacheable(InternalServerError, None)
  }

}

















