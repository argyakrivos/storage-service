package com.blinkbox.books.storageservice

import java.io.FileWriter

import akka.actor.{ActorRefFactory, Props}
import com.blinkbox.books.logging.DiagnosticExecutionContext
import com.blinkbox.books.rabbitmq.RabbitMqConfirmedPublisher.PublisherConfiguration
import com.blinkbox.books.rabbitmq.{RabbitMqConfirmedPublisher, RabbitMqConfig, RabbitMq}
import com.blinkbox.books.spray.{Directives => CommonDirectives}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.io.Source
import scala.util.control.NonFatal

trait MappingLoader {
  def load(path:String): String
  def write(path:String, json:String):Unit
}

case class FileMappingLoader() extends MappingLoader {
  override def load(path:String): String =
    Source.fromFile(path).mkString("")
  override def write(path:String, json: String): Unit = {
    val fw = new FileWriter(path)
    try {
      fw.write(json)
    }finally {
      fw.close()
    }
  }
}

class MessageSender(config: BlinkboxRabbitMqConfig, arf:ActorRefFactory){
  private val reliableConnection = RabbitMq.reliableConnection(RabbitMqConfig(config.serviceConfig))
  val publisherConfiguration: PublisherConfiguration = PublisherConfiguration(config.senderString)
  val qSender = arf.actorOf(Props(new RabbitMqConfirmedPublisher(reliableConnection, publisherConfiguration)), "QuarterMasterPublisher")
  val executionContext = DiagnosticExecutionContext(arf.dispatcher)
}

case class QuarterMasterService(appConfig: AppConfig, initMapping:Mapping, messageSender:MessageSender) {
  val storageWorker = new QuarterMasterStorageWorker(appConfig.swc)
  var mapping= initMapping


 def cleanUp(assetToken: AssetToken, label: Int): Future[Map[DelegateType, Status]] =
   storageWorker.cleanUp(assetToken, label).map(_.toMap)

  def storeAsset(bytes: Array[Byte], label: Int): Future[(AssetToken, Future[Map[DelegateType, Status]])] = Future {
    val assetToken = genToken(bytes)
    val f = storageWorker.storeAsset(assetToken, bytes, label)
    (assetToken, f)
  }

  def getStatus(token: AssetToken): Future[Map[DelegateType, Status]] =
    storageWorker.getStatus(token)

  def genToken(data: Array[Byte]): AssetToken = {
    val md = java.security.MessageDigest.getInstance("SHA-1")
    val ha =  new sun.misc.BASE64Encoder().encode(md.digest(data))
    new AssetToken(ha)
  }

  def updateAndBroadcastMapping(mappingStr: String): Future[String ] =
    (for {
      mapping <- Future{MappingHelper.fromJsonStr(mappingStr)}
      _ <- MappingHelper.store(appConfig.mappingpath, mapping)
      _ <- MappingHelper.broadcastUpdate(messageSender.qSender, appConfig.eventHeader, mapping)
    } yield mapping).recover { case _ => this.mapping }.map (MappingHelper.toJson)

  def set(newMapping:Mapping):Unit = this.mapping = newMapping

  def loadMapping(): Future[String] = {
      val oldMapping = this.mapping
      val loadAndSetFuture =for {
        newMapping <- MappingHelper.load(appConfig.mappingpath)
        _ = set(newMapping)
      }yield MappingHelper.toJson(newMapping)
      loadAndSetFuture.recover[String]{case _ => MappingHelper.toJson(oldMapping)}
  }
}
