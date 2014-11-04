package com.blinkbox.books.storageservice

import akka.actor.ActorRef
import com.blinkbox.books.json.DefaultFormats
import com.blinkbox.books.messaging.{Event, EventHeader, MediaType, JsonEventBody}
import com.blinkbox.books.spray.v2
import scala.concurrent.ExecutionContext.Implicits.global
import org.json4s.FieldSerializer
import org.json4s.jackson.JsonMethods
import org.json4s.jackson.Serialization._
import spray.http.DateTime

import scala.concurrent.Future

case class Status (eta:DateTime,  available:Boolean)
case class AssetData(timeStarted:DateTime, totalSize:Long)
case class Progress(assetData:AssetData, sizeWritten:Long )
case class UserId(id:String)
case class UrlTemplate(serviceName:String, template:String){
  implicit val formats = DefaultFormats + FieldSerializer[UrlTemplate]()
}

object Status  extends Ordering[Status]{

  val neverStatus= new Status(DateTime.MaxValue, false)
  def isDone(progress:Progress):Boolean = progress.sizeWritten >= progress.assetData.totalSize

  override def compare(a: Status,b: Status): Int = a.eta.clicks compare b.eta.clicks

  def earlierStatus(latestProgress:Progress, earliestStatus:Status):Status = {
    val that = toStatus(latestProgress)
    min (earliestStatus, that)
  }

  def toStatus(progress:Progress):Status = {
    val now = DateTime.now
    if (isDone(progress))
      new Status(now, true)
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

  def getStatus(progress:List[Progress], name:DelegateType):Status = progress.foldRight[Status](neverStatus)(earlierStatus)
}

case class AssetToken(token:String) {
  def toFileString():String = token
}

case class DelegateType(name:String)

case class DelegateKey(delegateType:DelegateType, assetToken:AssetToken)

object MappingHelper extends JsonMethods with v2.JsonSupport  {
  val EXTRACTOR_NAME = "extractor"
  val TEMPLATES_NAME = "templates"
  var loader: MappingLoader = new FileMappingLoader

  def fromJsonStr(jsonString: String): Mapping = {
    //extract
    val m = read[Option[Mapping]](jsonString)
    m.getOrElse(throw new IllegalArgumentException(s"cant parse jsonString: $jsonString"))
  }

  def toJson(m: Mapping): String =     write[Mapping](m)

  def store(mappingPath: String, mapping: Mapping): Future[Unit] =
    Future(MappingHelper.loader.write(mappingPath, MappingHelper.toJson(mapping)))

  def load(path:String): Future[Mapping] = Future(loader.load(path)).map(fromJsonStr(_))

  implicit val timeout = AppConfig.timeout

  implicit object MappingValue extends JsonEventBody[Mapping] {
    val jsonMediaType = MediaType("application/vnd.blinkbox.books.ingestion.quartermaster.v2+json")
  }

  def broadcastUpdate(qsender: ActorRef, eventHeader: EventHeader,  mapping: Mapping): Future[Any] = {
    import akka.pattern.ask
    qsender ? Event.json[Mapping](eventHeader, mapping)
  }
}
case class Mapping(extractor: String, templates: List[UrlTemplate])


