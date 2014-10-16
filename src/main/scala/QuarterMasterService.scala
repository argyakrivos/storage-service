package com.blinkbox.books.storageservice

import java.io._

import akka.actor.{ActorRef, Props}
import com.blinkbox.books.config.Configuration
import com.blinkbox.books.json.DefaultFormats
import com.blinkbox.books.messaging._
import com.blinkbox.books.spray.{v2, Directives => CommonDirectives}
import common.{AssetToken, UrlTemplate}
import org.json4s.FieldSerializer
import org.json4s.jackson.JsonMethods
import org.json4s.jackson.Serialization._
import spray.http.StatusCodes._
import spray.http.{MediaTypes, StatusCodes}
import spray.routing._
import spray.util.LoggingContext
import worker.{QuarterMasterStorageRoutes, QuarterMasterStorageService, StorageRequest}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.concurrent.{Await, Future}
import scala.io.Source
import scala.util.control.NonFatal


object Mapping extends JsonMethods with v2.JsonSupport {

  implicit val formats = DefaultFormats + FieldSerializer[Mapping]() + FieldSerializer[UrlTemplate]()

  val EXTRACTOR_NAME = "extractor"
  val TEMPLATES_NAME = "templates"


  def fromJsonStr(jsonString: String): Future[Mapping] = {
    Future {

      val m = read[Option[MappingRaw]](jsonString).map(new Mapping(_))

      m
    }.map { (_.getOrElse(throw new IllegalArgumentException(s"cant parse jsonString: $jsonString")))}
  }

  def toJson(m: Mapping): String = write[MappingRaw](m.m)

  def load(path: String): Future[Mapping] =  Future {
      Source.fromFile(path).mkString("")
    }.flatMap(fromJsonStr(_:String))



    implicit object Mapping extends JsonEventBody[Mapping] {
      val jsonMediaType = MediaType("application/vnd.blinkbox.books.ingestion.quartermaster.v2+json")
    }


}



case class MappingRaw(extractor: String, templates: List[UrlTemplate])
//TODO rename to mapping model
case class Mapping(m:MappingRaw)  extends JsonMethods  with v2.JsonSupport  with Configuration  {

  implicit val formats =  DefaultFormats + FieldSerializer[Mapping]() + FieldSerializer[UrlTemplate]()

  implicit val timeout = AppConfig.timeout

  def store(mappingPath:String):Future[Unit] = Future {
    val fw = new FileWriter(mappingPath)
    fw.write(Mapping.toJson(this))
    fw.close()
  }










  def broadcastUpdate(qsender: ActorRef, eventHeader:EventHeader): Future[Any] =   {

    import akka.pattern.ask

   qsender ? Event.json[Mapping](eventHeader, this)

    }

   val jsonMediaType: MediaType = MediaType("application/vnd.blinkbox.books.ingestion.quartermaster.v2+json")

}






trait RestRoutes extends HttpService {
  def getAll: Route
}





























//QuarterMasterConfig is like static config, probably not even useful for testing
//services will all be  of type (Mapping) => (Mapping, A) where A is generic, these will be hoisted into a DSL at some point... maybe
case class QuarterMasterService(appConfig:AppConfig) {
  var mapping: Mapping = Await.result(loadMapping, 1000 millis)
  //implicit val executionContext = appConfig.rmq.executionContext

  def _updateAndBroadcastMapping(mappingStr:String):Future[(Mapping, Any)] =   (for {
    mapping <- Mapping.fromJsonStr(mappingStr)
    _ <- mapping.store(appConfig.mappingpath)
    broadcaststatus <- mapping.broadcastUpdate(appConfig.rmq.qSender, appConfig.eventHeader)
  } yield (mapping, broadcaststatus)).recover[(Mapping, Any)] {
    case _ => (this.mapping, false)
  }


  def loadMapping():Future[Mapping] =
    Mapping.load(appConfig.mappingpath)



}

class QuarterMasterRoutes(qms:QuarterMasterService)  extends HttpServiceActor
with RestRoutes with CommonDirectives with v2.JsonSupport {

  implicit val timeout = AppConfig.timeout
  val appConfig = qms.appConfig
  val storageActor = appConfig.hsc.arf.actorOf(Props(new QuarterMasterStorageRoutes(new QuarterMasterStorageService(appConfig))),"storageActor")
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
          // import spray.httpx.SprayJsonSupport._
          val sc = StorageRequest(data, label)
          storageActor ! sc
          complete(StatusCodes.Accepted)
        }

      }
    }
  }


  val assetUploadStatus = path(appConfig.statusMappingUri) {
    import akka.pattern.ask
    get {
      parameters('token.as[String]).as(AssetToken) {
        (assetToken:AssetToken) =>
      respondWithMediaType(MediaTypes.`application/json`) {
        val f = storageActor ? assetToken
        complete(StatusCodes.OK)
      }
      }
    }
  }


  //should return 202
  val updateMappingRoute =
    post {
      parameters('mappingJson) {
        (mappingString: String) => {
          val f:Future[ (Mapping, Any)] = qms._updateAndBroadcastMapping(mappingString)
          respondWithMediaType(MediaTypes.`application/json`) {
            complete(StatusCodes.Accepted, f)
          }
        }
      }
    }

  val reloadMappingRoute = path(appConfig.refreshMappingUri) {
    get {

      respondWithMediaType(MediaTypes.`application/json`) {
        val futureMapping:Future[Mapping] = qms.loadMapping.map((m:Mapping) => {
          qms.mapping = m
          m
        })
        complete(StatusCodes.OK, futureMapping)

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







