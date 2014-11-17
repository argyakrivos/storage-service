package com.blinkbox.books.storageservice

import java.util.concurrent.TimeUnit

import akka.actor.ActorRefFactory
import akka.util.Timeout
import com.blinkbox.books.config.ApiConfig
import com.blinkbox.books.messaging.EventHeader
import com.typesafe.config.Config
import spray.http.Uri.Path

import scala.collection.JavaConverters._
import scala.collection.immutable.Set

case class BlinkboxRabbitMqConfig(c: Config) {
  val senderString = c.getConfig("service.qm.sender")
  val serviceConfig = c.getConfig("service.qm.mq")
  val eventHeader: EventHeader = EventHeader(c.getString("service.qm.sender.eventHeader"))
}

case class DelegateConfig(delegate: StorageDelegate, labels: Set[Label])

case class AppConfig(mc: MappingConfig, rmq: BlinkboxRabbitMqConfig, sc: StorageConfig, svc: ApiConfig)

case class MappingConfig(c:Config){
  val mappingEventHandler = EventHeader(c.getString("service.qm.mappingEventHandler"))
  val mappingPath = c.getString("service.qm.mappingPath")
  val minStorageDelegates = c.getInt("service.qm.storage.minStorageDelegates")
}

object AppConfig {
  implicit val timeout = Timeout(50L, TimeUnit.SECONDS)
  val apiConfigKey: String = "service.qm.api.public"

  def apply(c: Config, arf: ActorRefFactory) =
    new AppConfig(MappingConfig(c), BlinkboxRabbitMqConfig(c),  StorageConfig(c), ApiConfig(c, apiConfigKey))
}

case class StorageConfig(c: Config) {
  val localStorageLabels = c.getIntList("service.qm.storage.local.localStorageLabels").asScala.toSet.map(Integer2int(_: Integer))
  val localStoragePath = c.getString("service.qm.storage.local.localStoragePath")
  val localPath = c.getString("service.qm.storage.local.localPath")
}