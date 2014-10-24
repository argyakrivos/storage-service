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
import common.{DelegateKey, DelegateType, Progress}
import spray.http.Uri.Path
import worker.{LocalStorageDelegate, StorageDelegate}

import scala.collection.concurrent.TrieMap
import scala.collection.mutable.{HashMap, MultiMap}


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
case class DelegateConfig(delegate:StorageDelegate, labels:Set[Int])


//TODO: Really important that there isnt more than 1 delegate type in each set
class StorageWorkerConfig(delegateConfigs:Set[DelegateConfig]){

  def toImmutableMap[A,B](x:Map[A,collection.mutable.Set[B]]):Map[A,collection.immutable.Set[B]] = x.map((kv:((A,collection.mutable.Set[B]))) => (kv._1,kv._2.toSet)).toMap



  val delegates:Map[Int,Set[StorageDelegate]] = getDelegates(delegateConfigs)

    def getDelegates(delegateConfigs:Set[DelegateConfig]):Map[Int, Set[StorageDelegate]] = {
      val tmpMultiMap : MultiMap[Int, StorageDelegate] = new HashMap[Int, collection.mutable.Set[StorageDelegate]] with MultiMap[Int, StorageDelegate]
      delegateConfigs.map((dc: DelegateConfig) => dc.labels.map((label: Int) => tmpMultiMap.addBinding(label, dc.delegate)))
     toImmutableMap[Int, StorageDelegate](tmpMultiMap.toMap)
    }

  val delegateTypes = delegateConfigs.map((dc:DelegateConfig) => dc.delegate.delegateType)






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
  val repo:TrieMap[DelegateKey, Progress] = new TrieMap[DelegateKey, Progress]

  def apply(c:Config,arf:ActorRefFactory)={

    val deletgateConfigs = Set(DelegateConfig(new LocalStorageDelegate(repo, "/tmp/qm", new DelegateType("localStorage")), Set(1,3,4)))
    new AppConfig( RabbitMQConfig(c,arf),HealthServiceConfig(arf), new StorageConfig(arf), new StorageWorkerConfig(deletgateConfigs))
  }


}

case class StorageConfig(arf:ActorRefFactory) {
  val localPath="/tmp/assets/"

}