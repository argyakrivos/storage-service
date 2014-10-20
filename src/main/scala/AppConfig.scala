package com.blinkbox.books.storageservice

import java.util.concurrent.TimeUnit

import akka.actor.{ActorRefFactory, Props}
import akka.util.Timeout
import com.blinkbox.books.logging.DiagnosticExecutionContext
import com.blinkbox.books.messaging.EventHeader
import com.blinkbox.books.rabbitmq.RabbitMqConfirmedPublisher.PublisherConfiguration
import com.blinkbox.books.rabbitmq.{RabbitMq, RabbitMqConfig, RabbitMqConfirmedPublisher}
import com.blinkbox.books.spray.HealthCheckHttpService
import com.typesafe.config.Config
import common.Progress
import spray.http.Uri.Path
import worker.{DelegatedAssetToken, LocalStorageDelegate, StorageDelegate}

import scala.collection.concurrent.TrieMap


case class HealthServiceConfig(arf:ActorRefFactory){
  val healthService =
    new HealthCheckHttpService {
      override implicit def actorRefFactory = arf
      override val basePath = Path("/")
    }
}


case class RabbitMQConfig(c:Config, arf:ActorRefFactory){
  val publisherConfiguration: PublisherConfiguration =PublisherConfiguration(c.getConfig("service.qm.sender"))
  private val reliableConnection =RabbitMq.reliableConnection(RabbitMqConfig(c.getConfig("service.qm")))

  val qSender = arf.actorOf(Props(new RabbitMqConfirmedPublisher(reliableConnection, publisherConfiguration)), "QuarterMasterPublisher")

  val executionContext=DiagnosticExecutionContext(arf.dispatcher)
}

case class StorageWorkerConfig(){
  val localStoragePath="/tmp/qm"
  val repo = AppConfig.repo
  private val localStorageDelegate: LocalStorageDelegate = new LocalStorageDelegate(repo, localStoragePath)
  val delegates: collection.immutable.HashMap[Int, Set[StorageDelegate]] =
    collection.immutable.HashMap[Int,Set[StorageDelegate]]( 1 -> Seq(localStorageDelegate).toSet)

}

case class AppConfig(rmq:RabbitMQConfig, hsc:HealthServiceConfig, sc: StorageConfig, swc:StorageWorkerConfig){

  val mappingEventHandler = EventHeader("application/quartermaster+json")
  val mappingpath  = "/tmp/mapping.json"
  val mappingUri = "/quartermaster/mapping"
  val refreshMappingUri = mappingUri + "/refresh"
  val statusMappingUri = mappingUri + "/status"
  val eventHeader:EventHeader=  EventHeader("QuarterMasterUpdatePublisher")
}

object AppConfig {
  implicit val timeout= Timeout(50L, TimeUnit.SECONDS)
  val repo:TrieMap[DelegatedAssetToken,Progress] = new TrieMap[DelegatedAssetToken, Progress]()
  def apply(c:Config,arf:ActorRefFactory)={
    new AppConfig( RabbitMQConfig(c,arf),HealthServiceConfig(arf), new StorageConfig(arf), new StorageWorkerConfig())
  }


}

case class StorageConfig(arf:ActorRefFactory) {
  val localPath="/tmp/assets/"

}