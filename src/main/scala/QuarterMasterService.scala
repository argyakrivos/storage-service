package com.blinkbox.books.storageservice

import java.io._

import akka.actor.ActorRef
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
    read(jsonString)
  }catch {
    case e:Exception => None
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

case class Mapping (extractor: String, templates: List[UrlTemplate])  extends JsonMethods  with v2.JsonSupport with QuarterMasterConfig   {

  implicit val formats =  DefaultFormats + FieldSerializer[Mapping]() + FieldSerializer[UrlTemplate]()


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


class QuarterMasterService extends QuarterMasterConfig {
  var mapping: Mapping = Mapping.load(mappingpath).unsafePerformIO().get

def maybeBroadcast(sender:ActorRef,mappingStr:String):Option[(Mapping, Future[Any])] = for {
  maybeMapping <- Mapping.fromJsonStr(mappingStr)
  _ = maybeMapping.store(mappingpath)
  ioFuture = maybeMapping.broadcastUpdate(sender, eventHeader).unsafePerformIO()
} yield (maybeMapping, ioFuture)

//just mention what it takes extra data needed by spray
  def _updateAndBroadcastMapping(sender:ActorRef, executionContext:ExecutionContextExecutor)(mappingStr:String):State[Mapping,Future[Any]]
=   State[Mapping,Future[Any]]((oldMapping:Mapping) => maybeBroadcast(sender, mappingStr) match {
    case Some((newMapping:Mapping,future:Future[Any])) => (newMapping, future)
    case None => (oldMapping, Future{"done"}(executionContext))
  })


//requires the values required by qSender before
  val updateAndBroadcastMapping = for {
    qs <- qSender
    ec <- executionContext
  } yield _updateAndBroadcastMapping(qs, ec) _

}




class QuarterMasterRoutes(qms:QuarterMasterService)  extends HttpServiceActor with QuarterMasterConfig
    with RestRoutes with CommonDirectives with v2.JsonSupport  {

  val runtimeConfig = QuarterMasterRuntimeDeps(actorRefFactory)

  val mappingRoute = path(mappingUri) {
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
  def runStateForSpray(s:State[Mapping, Future[Any]]) : StandardRoute = {
    s.run(qms.mapping)  match {
      case (m:Mapping, f:Future[Any]) => {
        qms.mapping = m
        complete(f)
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

  val reloadMappingRoute = path(refreshMappingUri) {
      get {
        val mapping = Mapping.load(mappingpath).unsafePerformIO().get
        complete(mapping.toJson)
      }
    }



  val quarterMasterRoute: Route = mappingRoute ~ reloadMappingRoute ~ updateMappingRoute ~ healthService(runtimeConfig).routes
 def receive = runRoute(quarterMasterRoute)




  private def exceptionHandler(implicit log: LoggingContext) = ExceptionHandler {
    case NonFatal(e) =>
      log.error(e, "Unhandled error")
      uncacheable(InternalServerError, None)
  }

}

















