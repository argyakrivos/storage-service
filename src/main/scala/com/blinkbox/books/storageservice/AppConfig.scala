package com.blinkbox.books.storageservice

import java.util.concurrent.TimeUnit
import scala.collection.JavaConverters._
import akka.actor.{ActorRefFactory, Props}
import akka.util.Timeout
import com.blinkbox.books.logging.DiagnosticExecutionContext
import com.blinkbox.books.messaging.EventHeader
import com.blinkbox.books.rabbitmq.RabbitMqConfirmedPublisher.PublisherConfiguration
import com.blinkbox.books.rabbitmq.{RabbitMq, RabbitMqConfig, RabbitMqConfirmedPublisher}
import com.blinkbox.books.spray.HealthCheckHttpService
import com.typesafe.config.Config
import spray.http.Uri.Path
import scala.collection.concurrent.TrieMap
import scala.collection.mutable.{HashMap, MultiMap}

case class HealthServiceConfig(arf: ActorRefFactory) {
  val healthService =
    new HealthCheckHttpService {
      override implicit def actorRefFactory = arf
      override val basePath = Path("/")
    }
}

case class RabbitMQConfig(c: Config, arf: ActorRefFactory) {
  val publisherConfiguration: PublisherConfiguration = PublisherConfiguration(c.getConfig("service.qm.sender"))
  private val reliableConnection = RabbitMq.reliableConnection(RabbitMqConfig(c.getConfig("service.qm")))
  val qSender = arf.actorOf(Props(new RabbitMqConfirmedPublisher(reliableConnection, publisherConfiguration)), "QuarterMasterPublisher")
  val executionContext = DiagnosticExecutionContext(arf.dispatcher)
}

case class DelegateConfig(delegate: StorageDelegate, labels: Set[Int])

class StorageWorkerConfig(delegateConfigs: Set[DelegateConfig]) {
  def toImmutableMap[A, B](x: Map[A, collection.mutable.Set[B]]): Map[A, collection.immutable.Set[B]] = x.map((kv: ((A, collection.mutable.Set[B]))) => (kv._1, kv._2.toSet)).toMap
  val delegates: Map[Int, Set[StorageDelegate]] = getDelegates(delegateConfigs)
  def getDelegates(delegateConfigs: Set[DelegateConfig]): Map[Int, Set[StorageDelegate]] = {
    val tmpMultiMap: MultiMap[Int, StorageDelegate] = new HashMap[Int, collection.mutable.Set[StorageDelegate]] with MultiMap[Int, StorageDelegate]
    delegateConfigs.map((dc: DelegateConfig) => dc.labels.map((label: Int) => tmpMultiMap.addBinding(label, dc.delegate)))
    toImmutableMap[Int, StorageDelegate](tmpMultiMap.toMap)
  }
  val delegateTypes = delegateConfigs.map((dc: DelegateConfig) => dc.delegate.delegateType)
}

case class AppConfig(c:Config, rmq: RabbitMQConfig, hsc: HealthServiceConfig, sc: StorageConfig, swc: StorageWorkerConfig) {
  val mappingEventHandler = EventHeader(c.getString("service.qm.mappingEventHandler"))
  val mappingpath = c.getString("service.qm.mappingpath")
  val mappingUri = c.getString("service.qm.mappingUri")
  val refreshMappingUri = c.getString("service.qm.refreshMappingUri")
  val statusMappingUri = c.getString("service.qm.statusMappingUri")
  val eventHeader: EventHeader = EventHeader(c.getString("service.qm.sender.eventHeader"))
}

object AppConfig {

  implicit val timeout = Timeout(50L, TimeUnit.SECONDS)
  val repo: TrieMap[DelegateKey, Progress] = new TrieMap[DelegateKey, Progress]
  def apply(c: Config, arf: ActorRefFactory) = {
    val localStoragePath = c.getString("service.qm.localStoragePath")
    val  localstoragelabels:Set[Int] = c.getIntList("service.qm.localStorageLabels").asScala.toSet.map(Integer2int(_:Integer))
    val deletgateConfigs = Set(DelegateConfig(new LocalStorageDelegate(repo,localStoragePath, new DelegateType("localStorage")), localstoragelabels))
    new AppConfig(c,RabbitMQConfig(c, arf), HealthServiceConfig(arf), new StorageConfig(c,arf), new StorageWorkerConfig(deletgateConfigs))
  }
}

case class StorageConfig(c:Config, arf: ActorRefFactory) {
  val localPath = c.getString("service.qm.localPath")
}