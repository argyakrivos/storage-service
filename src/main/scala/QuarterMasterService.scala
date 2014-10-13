package com.blinkbox.books.storageservice

import java.io._

import akka.actor.ActorRef
import com.blinkbox.books.config.Configuration
import com.blinkbox.books.json.DefaultFormats
import com.blinkbox.books.messaging._
import com.blinkbox.books.spray.{v2, Directives => CommonDirectives}
import org.json4s.FieldSerializer
import org.json4s.jackson.JsonMethods
import org.json4s.jackson.Serialization.{read, write}
import spray.http.StatusCodes._
import spray.httpx.marshalling.ToResponseMarshallable
import spray.routing._
import spray.util.LoggingContext

import scala.concurrent.{ExecutionContextExecutor, Future}
import scala.io.Source
import scala.util.control.NonFatal
import scalaz.State
import scalaz.effect.IO



object Mapping extends JsonMethods with v2.JsonSupport {

  implicit val formats =  DefaultFormats + FieldSerializer[Mapping]() + FieldSerializer[UrlTemplate]()

  val EXTRACTOR_NAME = "extractor"
  val TEMPLATES_NAME = "templates"


  def fromJsonStr(jsonString :String):Option[Mapping] = try {
    println("****"+JsonMethods.parse(jsonString, formats.wantsBigDecimal))
    read[Option[Mapping]](jsonString)

//    val json = parse(req.body.map(bytes => new String(bytes, "UTF-8")) openOr "")
//    val request: UpdatedSource = json.extract[UpdatedSource]
  }catch {

    case e:Exception =>
      println(e.toString)
      e.printStackTrace
      None
  }

    def load(path: String): IO[Option[Mapping]] = IO {
      val jsonString = Source.fromFile(path).mkString("")
      fromJsonStr(jsonString)
    }

    implicit object Mapping extends JsonEventBody[Mapping] {
      val jsonMediaType = MediaType("mapping/update/v1.schema.json")
    }


}

case class UserId(id:String)

case class UrlTemplate(serviceName:String, template:String)

case class Mapping (extractor: String, templates: List[UrlTemplate])  extends JsonMethods  with v2.JsonSupport  with Configuration  {

  implicit val formats =  DefaultFormats + FieldSerializer[Mapping]() + FieldSerializer[UrlTemplate]()

  implicit val timeout = AppConfig(config).timeout

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


  def unapply(body: EventBody): Option[( String, List[UrlTemplate])]  = {
    val maybeMapping:Option[Mapping] = JsonEventBody.unapply[Mapping](body)
    maybeMapping.flatMap((m:Mapping) => Mapping.unapply(m) )
  }
}

//QuarterMasterConfig is like static config, probably not even useful for testing
class QuarterMasterService extends Configuration {
  val appConfig = AppConfig(config)
  var mapping: Mapping = Mapping.load(appConfig.mappingpath).unsafePerformIO().get

private def maybeBroadcast(sender:ActorRef,mappingStr:String):Option[(Mapping, IO[Future[Any]])] = for {
  maybeMapping <- Mapping.fromJsonStr(mappingStr)
  _ = maybeMapping.store(appConfig.mappingpath)
  ioFuture = maybeMapping.broadcastUpdate(sender, appConfig.eventHeader)
} yield (maybeMapping, ioFuture)

//just mention what it takes extra data needed by spray
 private def _updateAndBroadcastMapping(sender:ActorRef, executionContext:ExecutionContextExecutor)(mappingStr:String):State[Mapping, IO[Future[Any]]]
=   State[Mapping,IO[Future[Any]]]((oldMapping:Mapping) => maybeBroadcast(sender, mappingStr) match {
    case Some((newMapping:Mapping,iofuture:IO[Future[Any]])) => (newMapping, iofuture)
    case None => (oldMapping, IO{Future{"done"}(executionContext)})
  })


//requires the values required by qSender before
  val updateAndBroadcastMapping = for {
    qs <- appConfig.rmq.qSender
    ec <- appConfig.rmq.executionContext
  } yield _updateAndBroadcastMapping(qs, ec) _

}




class QuarterMasterRoutes(qms:QuarterMasterService)  extends HttpServiceActor with Configuration
    with RestRoutes with CommonDirectives with v2.JsonSupport  {

  val runtimeConfig = QuarterMasterRuntimeDeps(actorRefFactory)
  val appConfig = AppConfig(config)

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

  type SprayCompleteType  =  (⇒ ToResponseMarshallable) ⇒ StandardRoute
  def runStateForSpray(s:State[Mapping, IO[Future[Any]]]) : StandardRoute = {
    s.run(qms.mapping)  match {
      case (m:Mapping, f:IO[Future[Any]]) => {
        qms.mapping = m
        complete(f.unsafePerformIO())
      }
    }
  }

    //should return 200
   val  updateMappingRoute =
      post {
        parameters('mappingJson) {
          (qms.updateAndBroadcastMapping(runtimeConfig).apply(_:String)) andThen (runStateForSpray(_))
        }
      }

  val reloadMappingRoute = path(appConfig.refreshMappingUri) {
      get {
        val mapping = Mapping.load(appConfig.mappingpath).unsafePerformIO().get
        complete(mapping.toJson)
      }
    }



  val quarterMasterRoute = mappingRoute ~ reloadMappingRoute ~ updateMappingRoute ~ appConfig.hsc.healthService(runtimeConfig).routes
 def receive = runRoute(quarterMasterRoute)




  private def exceptionHandler(implicit log: LoggingContext) = ExceptionHandler {
    case NonFatal(e) =>
      log.error(e, "Unhandled error")
      uncacheable(InternalServerError, None)
  }

}

















