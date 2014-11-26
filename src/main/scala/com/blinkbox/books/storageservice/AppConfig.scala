package com.blinkbox.books.storageservice

import java.util.concurrent.TimeUnit

import akka.actor.ActorRefFactory
import akka.util.Timeout
import com.blinkbox.books.config.ApiConfig
import com.blinkbox.books.messaging.EventHeader
import com.blinkbox.books.rabbitmq.RabbitMqConfig
import com.typesafe.config.Config
import scala.collection.JavaConverters._
import scala.collection.immutable.Set

case class AppConfig(mapping: MappingConfig, rabbit: RabbitMqConfig, storage:Set[Config], api: ApiConfig)
case class MappingConfig(path: String, sender: Config, eventHeader: EventHeader, minStorageProviders: Int)

object MappingConfig {
  def apply(config: Config) = new MappingConfig(
    config.getString("service.qm.mappingPath"),
    config.getConfig("service.qm.sender"),
    EventHeader(config.getString("service.qm.sender.eventHeader")),
    config.getInt("service.qm.storage.minStorageProviders")
  )}

object AppConfig {
  implicit val timeout = Timeout(50L, TimeUnit.SECONDS)
  val apiConfigKey: String = "service.qm.api.public"
  def apply(c: Config, arf: ActorRefFactory) =
    new AppConfig(MappingConfig(c), RabbitMqConfig(c.getConfig("service.qm.mq")), Set(c), ApiConfig(c, apiConfigKey))
}

case class LocalStorageConfig(config: Config) extends NamedStorageConfig {
  val localStorageLabels = config.getIntList("service.qm.storage.providers.local.localStorageLabels").asScala.toSet.map(Integer2int(_: Integer))
  val localStoragePath = config.getString("service.qm.storage.providers.local.localStoragePath")
  val localPath = config.getString("service.qm.storage.providers.local.localPath")
  val providerId = config.getString("service.qm.storage.providers.local.providerId")
}

trait NamedStorageConfig  {
  val providerId:String
}