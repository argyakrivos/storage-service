package com.blinkbox.books.storageservice

import java.io._
import java.lang.Exception
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

import akka.actor.{Props, ActorRef}
import com.blinkbox.books.config.Configuration
import com.blinkbox.books.json.DefaultFormats
import com.blinkbox.books.messaging._
import com.blinkbox.books.spray.{v2, Directives => CommonDirectives}
import com.fasterxml.jackson.databind.JsonMappingException
import org.json4s.FieldSerializer
import org.json4s.jackson.JsonMethods
import org.json4s.jackson.Serialization._
import spray.http.StatusCodes._
import spray.http.{DateTime, MediaTypes, StatusCodes}
import spray.routing._
import spray.util.LoggingContext

import scala.collection.concurrent.TrieMap
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.{Await, Future}
import scala.io.Source
import scala.util.control.NonFatal
import scala.concurrent.duration._


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


case class Status (eta:DateTime,  available:Boolean)

case class AssetData(timeStarted:DateTime, totalSize:Long)
case class _AssetData(timeStarted:DateTime, totalSize:Long)
case class Progress(assetData:AssetData, sizeWritten:Long )




object Status  extends Ordering[Status]{

  val neverStatus:Status = new Status(DateTime.MaxValue, false)
  def isDone(progress:Progress):Boolean = progress.sizeWritten >= progress.assetData.totalSize

  override def compare(a: Status,b: Status): Int = a.eta.clicks compare b.eta.clicks

  def earlierStatus(latestProgress:Progress, earliestStatus:Status):Status = {
    val that = toStatus(latestProgress)
    min (earliestStatus, that)
  }

  def toStatus(progress:Progress):Status = {
    val now = DateTime.now
    if (isDone(progress))
       return new Status(now, true)
    else {
      val size = progress.assetData.totalSize
      val written = progress.sizeWritten
      val start = progress.assetData.timeStarted
      val unwritten = size - written
      val timeTakenMillis = now.clicks - start.clicks
      val bytesPerMillis = written / timeTakenMillis
      val etaClicks = unwritten / bytesPerMillis
      new Status(now + etaClicks, false)
    }
  }
  //TODO complete the status calcualtion and serialise
  def getStatus(progress:List[Progress]):Status = progress.foldRight[Status](neverStatus)(earlierStatus)
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

   val jsonMediaType: MediaType = MediaType("application/vnd.blinkbox.books.ingestion.quartermaster.v2+json")

}






trait RestRoutes extends HttpService {
  def getAll: Route


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
  def receive = {
    case StorageRequest(data,label) =>
     complete(qmss.storeAsset(genToken(data))(data))
    case token@AssetToken(_) =>
      complete(StatusCodes.OK, qmss.getStatus(token).get)

  }
}

class QuarterMasterStorageService(appConfig:AppConfig) extends StorageService {
  def getStatus(token: AssetToken):Option[Status] = repo.get(token) map (Status.toStatus(_))

  val repo:TrieMap[AssetToken,Progress] = new TrieMap[AssetToken, Progress]

  def getPath(assetToken:AssetToken):String={
    appConfig.sc.localPath ++ assetToken.token
  }

  def storeProgress = repo.put _

  def getProgress = repo.get  _

  def removeProgress(assetToken:AssetToken) ={
    repo.remove(assetToken)
  }

  def updateProgress(assetToken:AssetToken, size:Long, started:DateTime, bytesWritten:Long) ={

    repo.putIfAbsent(assetToken, new Progress(new AssetData(started, size), bytesWritten)).map(
      (oldProgress:Progress) => repo.put(assetToken,Progress(oldProgress.assetData, bytesWritten))
    )

  }


//this should fan out to other actors
  override def storeAsset(assetToken: AssetToken)(data: Array[Byte]):Future[AssetToken] = Future{
    // import spray.httpx.SprayJsonSupport._

    val fos: FileOutputStream = new FileOutputStream(getPath(assetToken));
    val channel = fos.getChannel
    val numBytes: Long = data.length
    val started: DateTime = DateTime.now
    updateProgress(assetToken,numBytes, started, 0)
    try {



      val  buf:ByteBuffer = ByteBuffer.allocate(48);
      buf.clear();
      buf.put(data);

      buf.flip();

      while(buf.hasRemaining()) {
        channel.write(buf);
        updateProgress(assetToken,numBytes, started, buf.position())
      }

      removeProgress(assetToken)
      assetToken

    } finally {
      fos.close();

    }
  }

  //returns an option of future if the token isnt in the cache nothing happens
  override def progress(assetToken: AssetToken): Future[Progress] =  Future {
    val assetData:AssetData = repo.get(assetToken).get.assetData
    val f: FileInputStream= new FileInputStream(getPath(assetToken));
    val fc:FileChannel = f.getChannel
    try {
      new Progress(assetData, fc.size)
    } finally {
      fc.close()
      f.close()
    }
  }
}











trait StorageService {

  def storeAsset(token:AssetToken)(data:Array[Byte]):Future[AssetToken]
  def progress(token:AssetToken):Future[Progress]

}




case class StorageRequest(data:Array[Byte], label : Int )


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
case class AssetToken(token:String)



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






