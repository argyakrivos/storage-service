import java.io._
import java.util.concurrent.TimeUnit

import akka.actor.{ActorRef, ActorRefFactory, Props}
import akka.pattern.ask
import akka.util.Timeout
import com.blinkbox.books.json.DefaultFormats
import com.blinkbox.books.logging.DiagnosticExecutionContext
import com.blinkbox.books.messaging.{Event, EventHeader, JsonEventBody}
import com.blinkbox.books.rabbitmq.RabbitMqConfirmedPublisher.PublisherConfiguration
import com.blinkbox.books.rabbitmq.{RabbitMq, RabbitMqConfig, _}
import com.blinkbox.books.spray.HealthCheckHttpService
import com.typesafe.config.Config
import org.json4s.JsonAST._
import org.json4s.JsonDSL._
import org.json4s.jackson.JsonMethods
import spray.http.Uri.Path
import spray.httpx.Json4sSupport
import spray.routing.HttpServiceActor

import scala.concurrent.Future
import scala.io.Source
import scalaz.{Id, Reader}
import scalaz.effect.IO


case class UserId(id:String)

case class UrlTemplate(serviceName:String, template:String)


object Mapping extends JsonMethods with Json4sSupport  {

  implicit val json4sJacksonFormats = DefaultFormats

  val json4sFormats = DefaultFormats
  val EXTRACTOR_NAME = "extractor"
  val TEMPLATES_NAME = "templates"
  val renderCompactly = (compact _) compose (render _)


  def parseMapping(jsonString: String): Mapping = {
    val json: JValue = parse(jsonString)
    json.extract[Mapping]
  }

  def toJson(mapping: Mapping): String = {
    renderCompactly((EXTRACTOR_NAME -> mapping.extractor) ~ (TEMPLATES_NAME -> mapping.templates))
  }




  def load(path: String): IO[Mapping] = IO {
    parseMapping(Source.fromFile(path).mkString(""))
  }

  def store(config: QuarterMasterConfig, mapping: Mapping): IO[Unit] =
    IO {
      val fw = new FileWriter(config.MAPPING_PATH)
      fw.write(toJson(mapping))
      fw.close()
    }





}



trait  QuarterMasterConfig {
  val MAPPING_EVENT_HEADER:EventHeader
  val MAPPING_PATH:String
  val MAPPING_URI:String
  val REFRESH_MAPPING_URI:String
  val QSENDER:ActorRef
  val HEALTH_SERVICE:HealthCheckHttpService

}
case class Mapping(extractor: String, templates: List[UrlTemplate])
{
    def toJson(): String = {
      Mapping.toJson(this)
    }

    def store(storePath:String): IO[Unit] =
      IO {
        val fw = new FileWriter(storePath)
        fw.write(toJson())
        fw.close()
      }

  def broadcastUpdate(qsender: ActorRef, eventHeader:EventHeader): IO[Future[Any]] = IO {
    val eventBody = JsonEventBody(toJson())
    qsender ? Event(eventHeader, eventBody)
  }
}

//http://blog.originate.com/blog/2013/10/21/reader-monad-for-dependency-injection/



val quarterMasterConfigGen  = Reader[Tuple2[ActorRefFactory, Config], QuarterMasterConfig]((arf:ActorRefFactory, c:Config) =>
new  QuarterMasterConfig {
  val MAPPING_EVENT_HEADER = EventHeader("QuarterMasterUpdatePublisher")
  val MAPPING_PATH  = "/tmp/mapping.json"
  val MAPPING_URI = "/quartermaster/mapping"
  val REFRESH_MAPPING_URI = MAPPING_URI map ( _ + "/refresh")
  val QSENDER = arf.actorOf(Props(new RabbitMqConfirmedPublisher(RabbitMq.reliableConnection(RabbitMqConfig(c)),  PublisherConfiguration(c))), "QuarterMasterPublisher")


  val HEALTH_SERVICE = new HealthCheckHttpService {
  override implicit def actorRefFactory = arf
  override val basePath = Path("/")
}
}

)




  class QuarterMasterApi(config:Config) extends HttpServiceActor   {
    override implicit def actorRefFactory: ActorRefFactory = this.actorRefFactory

    private val quarterMasterConfig = quarterMasterConfigGen((actorRefFactory,config))

    private val mappingpath:String = quarterMasterConfig.MAPPING_PATH
    var mapping:Mapping  = Mapping.load(mappingpath).unsafePerformIO()
    val mappingUri:String= quarterMasterConfig.MAPPING_URI
    val refreshMappingUri:String = quarterMasterConfig.REFRESH_MAPPING_URI
    val healthService:HealthCheckHttpService = quarterMasterConfig.HEALTH_SERVICE
    val qsender:ActorRef = quarterMasterConfig.QSENDER
    val eventHeader  = quarterMasterConfig.MAPPING_EVENT_HEADER


    implicit val executionContext = DiagnosticExecutionContext(actorRefFactory.dispatcher)

    val mappingRoute = path(mappingUri) {
      get {
        complete(mapping.toJson)
      }
    }

    //should return 200
    val updateMappingRoute = path(mappingUri) {
      post {
        parameters('mappingJson) {
          (mappingStr: String) => {
            mapping =Mapping.parseMapping(mappingStr)
            mapping.store(mappingpath)
            val f: Future[Any] = mapping.broadcastUpdate(qsender,eventHeader).unsafePerformIO()
            complete(f)
          }
        }
      }
    }

    val reloadMappingRoute =
      path(refreshMappingUri) {
        get {
          mapping = Mapping.load(mappingpath).unsafePerformIO()
          complete(mapping.toJson)
        }
     }

    val receive = runRoute(mappingRoute ~ reloadMappingRoute ~ healthService.routes)
  }









