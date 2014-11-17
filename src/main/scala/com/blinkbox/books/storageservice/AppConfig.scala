package com.blinkbox.books.storageservice

import java.util.concurrent.TimeUnit

import akka.actor.ActorRefFactory
import akka.util.Timeout
import com.blinkbox.books.messaging.EventHeader
import com.typesafe.config.Config
import spray.http.Uri.Path

import scala.collection.JavaConverters._
import scala.collection.immutable.Set

case class BlinkboxRabbitMqConfig(c: Config) {
  val senderString = c.getConfig("service.qm.sender")
  val serviceConfig = c.getConfig("service.qm.mq")
}

case class ProviderConfig(provider: StorageProvider, labels: Set[Label])

case class AppConfig(c: Config, rmq: BlinkboxRabbitMqConfig,  lsc: LocalStorageConfig) {
  val root = Path(c.getString("service.qm.api.public.root"))
  val host = c.getString("service.qm.api.public.host")
  val effectivePort = c.getInt("service.qm.api.public.effectivePort")
  val mappingEventHandler = EventHeader(c.getString("service.qm.mappingEventHandler"))
  val mappingPath = c.getString("service.qm.mappingPath")
  val eventHeader: EventHeader = EventHeader(c.getString("service.qm.sender.eventHeader"))
  val minStorageProviders = c.getInt("service.qm.storage.minStorageProviders")
}

object AppConfig {
  implicit val timeout = Timeout(50L, TimeUnit.SECONDS)
  def apply(c: Config, arf: ActorRefFactory) =
    new AppConfig(c, BlinkboxRabbitMqConfig(c),  LocalStorageConfig(c))
}

case class LocalStorageConfig(c: Config) extends NamedConfig{
  val localStorageLabels = c.getIntList("service.qm.storage.providers.local.localStorageLabels").asScala.toSet.map(Integer2int(_: Integer))
  val localStoragePath = c.getString("service.qm.storage.providers.local.localStoragePath")
  val localPath = c.getString("service.qm.storage.providers.local.localPath")
  val serviceName = c.getString("service.qm.storage.providers.local.serviceName")
}

trait NamedConfig {
  val serviceName:String
}