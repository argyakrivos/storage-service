package com.blinkbox.books.storageservice

import java.io.FileWriter

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
import spray.http.DateTime
import spray.util.NotImplementedException

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.io.Source

case class UserId(id: String)

case class AssetData(timeStarted: DateTime, totalSize: Long)

case class UrlTemplate(serviceName: String, template: String) {
  implicit val formats = DefaultFormats + FieldSerializer[UrlTemplate]()
}

case class AssetToken(token: String) {
  def toFileString(): String = token
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
    try {
      fw.write(json)
    } finally {
      fw.close()
    }
  }
}

object MappingHelper extends JsonMethods with v2.JsonSupport {
  implicit val timeout = AppConfig.timeout
  val EXTRACTOR_NAME = "extractor"
  val TEMPLATES_NAME = "templates"
  var loader: MappingLoader = new FileMappingLoader

  implicit object MappingValue extends JsonEventBody[Mapping] {
    val jsonMediaType = MediaType("application/vnd.blinkbox.books.ingestion.quartermaster.v2+json")
  }

  def fromJsonStr(jsonString: String): Mapping = {
    val m = read[Option[Mapping]](jsonString)
    m.getOrElse(throw new IllegalArgumentException(s"cant parse jsonString: $jsonString"))
  }

  def toJson(m: Mapping): String = write[Mapping](m)

  def store(mappingPath: String, mapping: Mapping): Future[Unit] =
    Future(MappingHelper.loader.write(mappingPath, MappingHelper.toJson(mapping)))

  def load(path: String): Future[Mapping] = Future(loader.load(path)).map(fromJsonStr(_))

  def broadcastUpdate(qsender: ActorRef, eventHeader: EventHeader, mapping: Mapping): Future[Any] = {
    qsender ? Event.json[Mapping](eventHeader, mapping)
  }
}

class MessageSender(config: BlinkboxRabbitMqConfig, arf: ActorRefFactory) {
  private val reliableConnection = RabbitMq.reliableConnection(RabbitMqConfig(config.serviceConfig))
  val publisherConfiguration: PublisherConfiguration = PublisherConfiguration(config.senderString)
  val qSender = arf.actorOf(Props(new RabbitMqConfirmedPublisher(reliableConnection, publisherConfiguration)), "QuarterMasterPublisher")
  val executionContext = DiagnosticExecutionContext(arf.dispatcher)
}

case class QuarterMasterService(appConfig: AppConfig, initMapping: Mapping, messageSender: MessageSender, storageManager: StorageManager) {
  var mapping = initMapping

  def cleanUp(assetToken: AssetToken, label: Int): Future[Map[DelegateType, Status]] =
    storageManager.cleanUp(assetToken, label).map(_.toMap)

  def storeAsset(bytes: Array[Byte], label: Int): Future[(AssetToken, Future[Map[DelegateType, Status]])] = Future {
    if (storageManager.getDelegatesForLabel(label).size < appConfig.minStorageDelegates) {
      throw new NotImplementedException(s"label $label is has no available storage delegates")
    }
    if (bytes.size < 1) {
      throw new IllegalArgumentException(s"no data")
    }
    val assetToken = genToken(bytes)
    val f = storageManager.storeAsset(assetToken, bytes, label)
    (assetToken, f)
  }

  def getStatus(token: AssetToken): Future[Map[DelegateType, Status]] =
    storageManager.getStatus(token)

  def genToken(data: Array[Byte]): AssetToken = {
    val md = java.security.MessageDigest.getInstance("SHA-1")
    val ha = new sun.misc.BASE64Encoder().encode(md.digest(data))
    new AssetToken(ha)
  }

  def updateAndBroadcastMapping(mappingStr: String): Future[String] =
    (for {
      mapping <- Future {
        MappingHelper.fromJsonStr(mappingStr)
      }
      _ <- MappingHelper.store(appConfig.mappingpath, mapping)
      _ <- MappingHelper.broadcastUpdate(messageSender.qSender, appConfig.eventHeader, mapping)
    } yield mapping).recover { case _ => this.mapping}.map(MappingHelper.toJson)

  def set(newMapping: Mapping): Unit = this.mapping = newMapping

  def loadMapping(): Future[String] = {
    val oldMapping = this.mapping
    val loadAndSetFuture = for {
      newMapping <- MappingHelper.load(appConfig.mappingpath)
      _ = set(newMapping)
    } yield MappingHelper.toJson(newMapping)
    loadAndSetFuture.recover[String] { case _ => MappingHelper.toJson(oldMapping)}
  }
}