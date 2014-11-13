package com.blinkbox.books.storageservice

import java.util.concurrent.TimeUnit

import akka.actor.ActorRefFactory
import akka.util.Timeout
import com.blinkbox.books.messaging.EventHeader
import com.blinkbox.books.spray.HealthCheckHttpService
import com.typesafe.config.Config
import spray.http.Uri.Path

import scala.collection.JavaConverters._
import scala.collection.immutable.Set
import scala.collection.immutable.Set

case class BlinkboxRabbitMqConfig(c: Config) {
  val senderString = c.getConfig("service.qm.sender")
  val serviceConfig = c.getConfig("service.qm")
}

case class DelegateConfig(delegate: StorageDelegate, labels: Set[Label])

case class AppConfig(c: Config, rmq: BlinkboxRabbitMqConfig,  sc: StorageConfig) {
  val root = Path(c.getString("service.qm.api.public.root"))
  val host = c.getString("service.qm.api.public.host")
  val effectivePort = c.getInt("service.qm.api.public.effectivePort")
  val mappingEventHandler = EventHeader(c.getString("service.qm.mappingEventHandler"))
  val mappingpath = c.getString("service.qm.mappingpath")
  val eventHeader: EventHeader = EventHeader(c.getString("service.qm.sender.eventHeader"))
  val minStorageDelegates = c.getInt("service.qm.storage.minStorageDelegates")
}

object AppConfig {
  implicit val timeout = Timeout(50L, TimeUnit.SECONDS)
  def apply(c: Config, arf: ActorRefFactory) =
    new AppConfig(c, BlinkboxRabbitMqConfig(c),  StorageConfig(c))
}

case class StorageConfig(c: Config) {
  val localstoragelabels = c.getIntList("service.qm.localStorageLabels").asScala.toSet.map(Integer2int(_: Integer))
  val localStoragePath = c.getString("service.qm.localStoragePath")
  val localPath = c.getString("service.qm.storage.local.localPath")
}