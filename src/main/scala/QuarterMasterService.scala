package com.blinkbox.books.storageservice

import java.io._
import java.nio.channels.FileChannel

import akka.actor.ActorRef
import com.blinkbox.books.config.Configuration
import com.blinkbox.books.json.DefaultFormats
import com.blinkbox.books.messaging._
import com.blinkbox.books.spray.{v2, Directives => CommonDirectives}
import org.json4s.FieldSerializer
import org.json4s.jackson.JsonMethods
import org.json4s.jackson.Serialization._
import spray.http.StatusCodes._
import spray.http.{DateTime, MediaTypes, StatusCodes}
import spray.routing._
import spray.util.LoggingContext

import scala.collection.concurrent.TrieMap
import scala.concurrent.Future
import scala.io.Source
import scala.util.control.NonFatal



object Mapping extends JsonMethods with v2.JsonSupport {

  implicit val formats = DefaultFormats + FieldSerializer[Mapping]() + FieldSerializer[UrlTemplate]()

  val EXTRACTOR_NAME = "extractor"
  val TEMPLATES_NAME = "templates"


  def fromJsonStr(jsonString: String): Future[Mapping] = Future{

    read[Option[MappingRaw]](jsonString).map(new Mapping(_))
  }.map
  {
    case Some(mapping:Mapping) => mapping
    case None => throw new IllegalArgumentException(s"cant parse jsonString: $jsonString")
  }


  def toJson(m: Mapping): String = write[MappingRaw](m.m)

  def load(path: String): Future[Mapping] =  Future {
      Source.fromFile(path).mkString("")
    }.flatMap(fromJsonStr(_:String))



    implicit object Mapping extends JsonEventBody[Mapping] {
      val jsonMediaType = MediaType("mapping/update/v1.schema.json")
    }


}


case class Status (eta:DateTime,  available:Boolean)
case class AssetData(timeStarted:DateTime, totalSize:Long)
case class Progress(assetData:AssetData, sizeWritten:Long )


object Status{
  //TODO complete the status calcualtion and serialise
  def getStatus(progress:List[Progress]):Status = new Status(null, null)
  //various f
}

case class UserId(id:String)

case class UrlTemplate(serviceName:String, template:String)

//TODO rename to mapping val
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

   val jsonMediaType: MediaType = MediaType("application/quatermaster+json")

}






trait RestRoutes extends HttpService {
  def getAll: Route


}

//QuarterMasterConfig is like static config, probably not even useful for testing
//services will all be  of type (Mapping) => (Mapping, A) where A is generic, these will be hoisted into a DSL at some point... maybe
case class QuarterMasterService(appConfig:AppConfig) {
  var mapping: Mapping = null


 def _updateAndBroadcastMapping(mappingStr:String):Future[(Mapping, Any)] = for {
  mapping <- Mapping.fromJsonStr(mappingStr)
  _ <- mapping.store(appConfig.mappingpath)
  broadcaststatus <- mapping.broadcastUpdate(appConfig.rmq.qSender, appConfig.eventHeader)
} yield (mapping, broadcaststatus)




  def loadMapping():Future[Mapping] =
    Mapping.load(appConfig.mappingpath)



}

class QuarterMasterStorageService(appConfig:AppConfig) extends StorageService {
   val repo:TrieMap[AssetToken,AssetData] = new TrieMap[AssetToken, AssetData]

  def getPath(assetToken:AssetToken):String={
      appConfig.sc.localPath ++ assetToken.token
  }

  def storeStatus(assetToken:AssetToken, assetData:AssetData) ={
    repo.put(assetToken,assetData)
  }

  override def storeAsset(assetToken: AssetToken)(data: Array[Byte]):Future[AssetToken] = Future{
    // import spray.httpx.SprayJsonSupport._

    val fos: FileOutputStream = new FileOutputStream(getPath(assetToken));
    try {
      storeStatus(assetToken, new AssetData(DateTime.now, 0))
      fos.write(data);
      assetToken
    } finally {
      fos.close();
      repo.remove(assetToken)
    }
  }

 //returns an option of future if the token isnt in the cache nothing happens
   override def progress(assetToken: AssetToken): Option[Future[Progress]] = repo.get(assetToken).map( (assetData:AssetData) => Future {
    val f: FileInputStream= new FileInputStream(getPath(assetToken));
    val fc:FileChannel = f.getChannel
    try {

     new Progress(assetData, fc.size)

    } finally {
      fc.close()
      f.close()
    }
  })
}

class QuarterMasterStorageRoutes(qmss:QuarterMasterStorageService) extends HttpServiceActor with RestRoutes with CommonDirectives with v2.JsonSupport {


  override def getAll: Route = {
    get {
      pathEndOrSingleSlash {
        uncacheable(InternalServerError, None)
      }
    }
  }

  def genToken(data:Array[Byte]):AssetToken = new AssetToken(data.hashCode.toString)


  val storeAssetRoute = {

    path("upload") {
      post {

        formField('data.as[Array[Byte]]) {


          (data:Array[Byte]) => {
            val res = qmss.storeAsset(genToken(data))(data)
             respondWithMediaType(MediaTypes.`application/json`) {
              complete(StatusCodes.Accepted, "some status")
            }
          }
        }

      }
    }
  }

  val quarterMasterStorageRoute = storeAssetRoute
  def receive = runRoute(quarterMasterStorageRoute)
}


class QuarterMasterRoutes(qms:QuarterMasterService)  extends HttpServiceActor
    with RestRoutes with CommonDirectives with v2.JsonSupport {


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
          // import spray.httpx.SprayJsonSupport._
          val fos: FileOutputStream = new FileOutputStream("test.png");
          try {
            fos.write(data);
          } finally {
            fos.close();
          }
          respondWithMediaType(MediaTypes.`application/json`) {
            complete(StatusCodes.Accepted, "some status")
          }
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


case class AssetToken(token:String)

trait StorageService {



  def storeAsset(token:AssetToken)(data:Array[Byte]):Future[AssetToken]
  def progress(token:AssetToken):Progress

}

















