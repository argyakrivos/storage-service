package com.blinkbox.books.storageservice

import java.util.concurrent.TimeUnit

import akka.actor.{ActorContext, Props}
import akka.util.Timeout
import com.blinkbox.books.config.Configuration
import com.blinkbox.books.logging.DiagnosticExecutionContext
import com.blinkbox.books.messaging.EventHeader
import com.blinkbox.books.rabbitmq.RabbitMqConfirmedPublisher.PublisherConfiguration
import com.blinkbox.books.rabbitmq.{RabbitMq, RabbitMqConfig, RabbitMqConfirmedPublisher}
import com.blinkbox.books.spray.HealthCheckHttpService
import spray.http.Uri.Path

import scala.concurrent.ExecutionContextExecutor
import scalaz._

//dependencies that are not available until runtime
case class QuarterMasterRuntimeDeps(arf:ActorContext)

trait QuarterMasterConfig extends Configuration{
  implicit val timeout= Timeout(50L, TimeUnit.SECONDS)
  val mappingEventHandler = EventHeader("QuarterMasterUpdatePublisher")
  val mappingpath  = "/tmp/mapping.json"
  val mappingUri = "/quartermaster/mapping"
  val refreshMappingUri = mappingUri + "/refresh"
  val eventHeader:EventHeader=  EventHeader("QuarterMasterUpdatePublisher")
  val publisherConfiguration =PublisherConfiguration(config)
  private val reliableConnection =RabbitMq.reliableConnection(RabbitMqConfig(config))

  private val publisher =  new RabbitMqConfirmedPublisher(reliableConnection,publisherConfiguration)

  val qSender =  Reader(
    (deps:QuarterMasterRuntimeDeps) =>  deps.arf.actorOf(Props(publisher), "QuarterMasterPublisher")

  )

   val executionContext:Reader[QuarterMasterRuntimeDeps, ExecutionContextExecutor] = Reader(
    (deps:QuarterMasterRuntimeDeps) => DiagnosticExecutionContext(deps.arf.dispatcher) )

  val healthService = Reader( (deps:QuarterMasterRuntimeDeps) =>
    new HealthCheckHttpService {
      override implicit def actorRefFactory = deps.arf
      override val basePath = Path("/")
    } )
}