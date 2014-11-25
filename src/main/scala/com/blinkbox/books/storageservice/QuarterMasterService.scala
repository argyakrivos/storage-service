package com.blinkbox.books.storageservice

import java.io.FileWriter

import akka.actor.{ActorRefFactory, Props}
import akka.pattern.ask
import com.blinkbox.books.json.DefaultFormats
import com.blinkbox.books.logging.DiagnosticExecutionContext
import com.blinkbox.books.messaging.{Event, JsonEventBody, MediaType}
import com.blinkbox.books.rabbitmq.RabbitMqConfirmedPublisher.PublisherConfiguration
import com.blinkbox.books.rabbitmq.{RabbitMq, RabbitMqConfirmedPublisher}
import com.blinkbox.books.spray.{v2, Directives => CommonDirectives}
import org.json4s.{Extraction, FieldSerializer}
import org.json4s.jackson.JsonMethods
import org.json4s.jackson.Serialization._
import org.slf4j.LoggerFactory
import spray.http.DateTime
import spray.util.NotImplementedException

import scala.collection.SeqView
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.io.Source
import scala.util.control.NonFatal
import scala.util.matching.Regex

case class UserId(id: String)
case class LocationTemplate(template:String)
case class AssetData(timeStarted: DateTime, totalSize: Long)
case class ProviderPair(id:ProviderId,template:LocationTemplate)

case class ProviderConfig(label: String, extractor: String, providers: Map[String, String]) {
  implicit val formats = DefaultFormats + FieldSerializer[ProviderConfig]() + FieldSerializer[ProviderId]() + FieldSerializer[LocationTemplate]()
  val regex = new Regex(extractor)
  def matches(digest: AssetDigest): Boolean = regex.findFirstMatchIn(digest.url).isDefined

  def getFullUrl(accString:String, tuple:(String,Int)):String = tuple match {
    case  (groupVal, groupNum) => {
      accString.replaceAll("""\\'""" +(groupNum+1) + """'""", groupVal)
    }
  }

//  def createUrlFrom(digest:AssetDigest):Option[String] =
//    for {
//      firstMatch <- regex.findFirstMatchIn(digest.url)
//    } yield firstMatch.subgroups.view.zipWithIndex.foldLeft(template)(getFullUrl)

}

case class AssetDigest(url:String) {
  val re = """bbbmap:(\w+):(\S+)""".r
  val re(label, sha1) = url
  def toFileString(): String = url
}

object GenAssetDigest{
  def apply(data: Array[Byte], label:String): AssetDigest = {
    val md = java.security.MessageDigest.getInstance("SHA-1")
    val labelString = label
    val sha1 = new sun.misc.BASE64Encoder().encode(md.digest(data))
    AssetDigest(s"bbbmap:$labelString:$sha1")
  }
}
case class Mapping(providers: List[ProviderConfig])

trait MappingLoader {
  def load(path: String): String
  def write(path: String, json: String): Unit
}

case class FileMappingLoader() extends MappingLoader {
  override def load(path: String): String =
    Source.fromFile(path).mkString("")

  override def write(path: String, json: String): Unit = {
    val fw = new FileWriter(path)
    try fw.write(json)
    finally fw.close()
  }
}

case class MappingHelper(loader: MappingLoader) extends JsonMethods with v2.JsonSupport {
  val extractorName = "extractor"
  val templatesName = "templates"

  def fromJsonStr(jsonString: String): Mapping = {
    val m = read[Option[List[ProviderConfig]]](jsonString).map(Mapping)
    m.getOrElse(throw new IllegalArgumentException(s"cant parse jsonString: $jsonString"))
  }

  def toJsonStr(m:Mapping):String = write[Mapping](m)

  def toJson(m: Mapping): String = compact(render(Extraction.decompose(m.providers)))

  def store(mappingPath: String, mapping: Mapping): Future[Unit] =
    Future(loader.write(mappingPath, toJson(mapping)))

  def load(path: String): Future[Mapping] = Future(loader.load(path)).map(fromJsonStr)
}

class MessageSender(config: AppConfig, arf: ActorRefFactory) extends JsonMethods with v2.JsonSupport {
  val eventHeader = config.mapping.eventHeader
  private val reliableConnection = RabbitMq.reliableConnection(config.rabbitmq)
  val publisherConfiguration = PublisherConfiguration(config.mapping.sender)
  val qSender = arf.actorOf(Props(new RabbitMqConfirmedPublisher(reliableConnection, publisherConfiguration)), "QuarterMasterPublisher")
  val executionContext = DiagnosticExecutionContext(arf.dispatcher)
  implicit val timeout = AppConfig.timeout
  implicit object MappingValue extends JsonEventBody[Mapping] {
    val jsonMediaType = MediaType("application/vnd.blinkbox.books.mapping.update.v1+json")
  }

  def broadcastUpdate(mapping: Mapping): Future[Any] = {
    qSender ? Event.json[Mapping](eventHeader, mapping)
  }
}

case class QuarterMasterService(appConfig: AppConfig,  messageSender: MessageSender, storageManager: StorageManager, mappingHelper: MappingHelper) {

  val log = LoggerFactory.getLogger(classOf[QuarterMasterRoutes])

  def cleanUp(assetDigest: AssetDigest): Future[Map[String, Status]] =
    storageManager.cleanUp(assetDigest).map(_.toMap)

  def storeAsset(bytes: Array[Byte], label: String): Future[(AssetDigest, Future[Map[String, Status]])] = Future {
    if (storageManager.getProvidersForLabel(label).size < appConfig.mapping.minStorageProviders) {
      throw new NotImplementedException(s"label $label is has no available storage providers")
    }
    if (bytes.size < 1) {
      throw new IllegalArgumentException(s"no data")
    }
    storageManager.storeAsset(label, bytes)
  }

  def getStatus(assetDigest: AssetDigest): Future[Map[String, Status]] =
    storageManager.getStatus(assetDigest)

  def updateAndBroadcastMapping(mappingStr: String): Future[String] ={
    val oldMapping = storageManager.mapping.get
    val storeAndBroadcastFuture = for {
      newMapping <- Future(mappingHelper.fromJsonStr(mappingStr))
      _ = storageManager.mapping.compareAndSet(oldMapping,newMapping)
      _ <- mappingHelper.store(appConfig.mapping.path, newMapping)
      _ <- messageSender.broadcastUpdate(newMapping)
    } yield mappingHelper.toJson(newMapping)
    storeAndBroadcastFuture.onFailure {
      case NonFatal(e) =>
        log.error(s"couldnt storeAndBroadcast mapping for $mappingStr",e)
        storageManager.mapping.set(oldMapping)}
    storeAndBroadcastFuture
  }

  def loadMapping(): Future[String] = {
    val oldMapping = storageManager.mapping.get
    val loadAndSetFuture = for {
      newMapping <- mappingHelper.load(appConfig.mapping.path)
      _ = storageManager.mapping.compareAndSet(oldMapping,newMapping)
    } yield mappingHelper.toJson(newMapping)
    loadAndSetFuture.onFailure{
      case NonFatal(e) =>
        log.error("couldnt load mapping",e)
        storageManager.mapping.set(oldMapping)
    }
    loadAndSetFuture
  }
}
