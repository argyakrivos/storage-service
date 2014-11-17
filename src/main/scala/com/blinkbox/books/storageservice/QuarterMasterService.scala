package com.blinkbox.books.storageservice

import java.io.FileWriter
import java.util.concurrent.atomic.AtomicReference
import akka.actor.{ActorRef, ActorRefFactory, Props}
import akka.pattern.ask
import com.blinkbox.books.json.DefaultFormats
import com.blinkbox.books.logging.DiagnosticExecutionContext
import com.blinkbox.books.messaging.{Event, EventHeader, JsonEventBody, MediaType}
import com.blinkbox.books.rabbitmq.RabbitMqConfirmedPublisher.PublisherConfiguration
import com.blinkbox.books.rabbitmq.{RabbitMq, RabbitMqConfig, RabbitMqConfirmedPublisher}
import com.blinkbox.books.spray.{v2, Directives => CommonDirectives}
import org.json4s.FieldSerializer
import org.json4s.jackson.JsonMethods
import org.json4s.jackson.Serialization._
import org.slf4j.LoggerFactory
import spray.http.DateTime
import spray.util.NotImplementedException

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.io.Source
import scala.util.control.NonFatal

case class UserId(id: String)
case class Label(label:String){
}
case class AssetData(timeStarted: DateTime, totalSize: Long)
case class UrlTemplate(serviceName: String, template: String) {
  implicit val formats = DefaultFormats + FieldSerializer[UrlTemplate]()
}

case class AssetDigest(url:String) {
  val re = """bbbmap:(\w+):(\S+)""".r
  val re(l, sha1) = url
  val label2 = Label(l)
  def toFileString(): String = url
}

object GenAssetDigest{
  def apply(data: Array[Byte], label:Label): AssetDigest = {
    val md = java.security.MessageDigest.getInstance("SHA-1")
    val labelString = label.label
    val sha1 = new sun.misc.BASE64Encoder().encode(md.digest(data))
    AssetDigest(s"bbbmap:$labelString:$sha1")
  }
}

case class Mapping(extractor: String, templates: List[UrlTemplate])

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

object MappingHelper extends JsonMethods with v2.JsonSupport {
  implicit val timeout = AppConfig.timeout
  val extractorName = "extractor"
  val templatesName = "templates"
  var loader: MappingLoader = new FileMappingLoader

  implicit object MappingValue extends JsonEventBody[Mapping] {
    val jsonMediaType = MediaType("application/vnd.blinkbox.books.mapping.update.v1+json")
  }

  def fromJsonStr(jsonString: String): Mapping = {
    val m = read[Option[Mapping]](jsonString)
    m.getOrElse(throw new IllegalArgumentException(s"cant parse jsonString: $jsonString"))
  }

  def toJson(m: Mapping): String =  write[Mapping](m)

  def store(mappingPath: String, mapping: Mapping): Future[Unit] =
    Future(MappingHelper.loader.write(mappingPath, MappingHelper.toJson(mapping)))

  def load(path: String): Future[Mapping] = Future(loader.load(path)).map(fromJsonStr)

  def broadcastUpdate(qsender: ActorRef, eventHeader: EventHeader, mapping: Mapping): Future[Any] = {
    qsender ? Event.json[Mapping](eventHeader, mapping)
  }
}

class MessageSender(config: AppConfig, arf: ActorRefFactory) {
  private val reliableConnection = RabbitMq.reliableConnection(config.rabbitmq)
  val publisherConfiguration = PublisherConfiguration(config.mapping.sender)
  val qSender = arf.actorOf(Props(new RabbitMqConfirmedPublisher(reliableConnection, publisherConfiguration)), "QuarterMasterPublisher")
  val executionContext = DiagnosticExecutionContext(arf.dispatcher)
}

case class QuarterMasterService(appConfig: AppConfig, initMapping: Mapping, messageSender: MessageSender, storageManager: StorageManager) {
  val mapping= new AtomicReference(initMapping)
  val log = LoggerFactory.getLogger(classOf[QuarterMasterRoutes])
  def cleanUp(assetToken: AssetDigest, label:Label): Future[Map[ProviderType, Status]] =
    storageManager.cleanUp(assetToken, label).map(_.toMap)

  def storeAsset(bytes: Array[Byte], label: Label): Future[(AssetDigest, Future[Map[ProviderType, Status]])] = Future {
    val providersForLabel: Set[StorageProvider] = storageManager.getProvidersForLabel(label)
    if (providersForLabel.size < appConfig.storage.minStorageProviders) {
        throw new NotImplementedException(s"label $label is has no available storage providers")
      }
      if (bytes.size < 1) {
        throw new IllegalArgumentException(s"no data")
      }
      val assetToken = GenAssetDigest(bytes,label)
      val f = storageManager.storeAsset(assetToken, bytes, label)
      (assetToken, f)
  }

  def getStatus(token: AssetDigest): Future[Map[ProviderType, Status]] =
    storageManager.getStatus(token)

  def updateAndBroadcastMapping(mappingStr: String): Future[String] ={
    val oldMapping = this.mapping.get
    val storeAndBroadcastFuture = for {
      mapping <- Future(MappingHelper.fromJsonStr(mappingStr))
      _ = set(mapping)
      _ <- MappingHelper.store(appConfig.mapping.path, mapping)
      _ <- MappingHelper.broadcastUpdate(messageSender.qSender, appConfig.mapping.eventHeader, mapping)
    } yield MappingHelper.toJson(mapping)
    storeAndBroadcastFuture.onFailure {
      case NonFatal(e) =>
        log.error("couldnt storeAndBroadcast mapping",e)
        this.mapping.set(oldMapping)}
    storeAndBroadcastFuture
  }

  def set(newMapping: Mapping): Unit = this.mapping.set(newMapping)

  def loadMapping(): Future[String] = {
    val oldMapping = this.mapping.get
    val loadAndSetFuture = for {
      newMapping <- MappingHelper.load(appConfig.mapping.path)
      _ = set(newMapping)
    } yield MappingHelper.toJson(newMapping)
    loadAndSetFuture.onFailure{
      case NonFatal(e) =>
        log.error("couldnt load mapping",e)
        this.mapping.set(oldMapping)
    }
    loadAndSetFuture
  }
}
