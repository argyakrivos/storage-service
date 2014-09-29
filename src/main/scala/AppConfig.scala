import java.util.concurrent.TimeUnit

import akka.actor.{ActorRefFactory, Props}
import akka.util.Timeout
import com.blinkbox.books.messaging.EventHeader
import com.blinkbox.books.rabbitmq.RabbitMqConfirmedPublisher.PublisherConfiguration
import com.blinkbox.books.rabbitmq.{RabbitMq, RabbitMqConfig, RabbitMqConfirmedPublisher}
import com.blinkbox.books.spray.HealthCheckHttpService
import com.typesafe.config.Config
import spray.http.Uri.Path

import scalaz._

trait  QuarterMasterConfig{
  implicit val timeout= Timeout(50L, TimeUnit.SECONDS)
  val mappingEventHandler = EventHeader("QuarterMasterUpdatePublisher")
  val mappingpath  = "/tmp/mapping.json"
  val mappingUri = "/quartermaster/mapping"
  val refreshMappingUri = mappingUri + "/refresh"
  val eventHeader:EventHeader=  EventHeader("QuarterMasterUpdatePublisher")
  val publisherConfiguration = Reader(PublisherConfiguration(_:Config))
  private val reliableConnection = Reader((c:Config) => RabbitMq.reliableConnection(RabbitMqConfig(c)))

  private val publisher = for {
    rc <- reliableConnection
    p <- publisherConfiguration
  } yield new RabbitMqConfirmedPublisher(rc,p)

  val qSender =  Reader((arf : ActorRefFactory) => for {
    p <- publisher
  }  yield (arf.actorOf(Props(p),"QuarterMasterPublisher")))

  val healthService = Reader( (arf:ActorRefFactory) =>
    new HealthCheckHttpService {
      override implicit def actorRefFactory = arf
      override val basePath = Path("/")
    } )
}