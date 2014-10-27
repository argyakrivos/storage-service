package com.blinkbox.books.storageservice

import java.io.FileWriter

import akka.actor.ActorRef
import com.blinkbox.books.config.Configuration
import com.blinkbox.books.json.DefaultFormats
import com.blinkbox.books.messaging.{Event, EventHeader, JsonEventBody, MediaType}
import com.blinkbox.books.spray.v2
import org.json4s.FieldSerializer
import org.json4s.jackson.JsonMethods
import org.json4s.jackson.Serialization._

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.io.Source

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

object Mapping extends JsonMethods with v2.JsonSupport  {
  implicit val formats = DefaultFormats + FieldSerializer[Mapping]() + FieldSerializer[UrlTemplate]()
  val EXTRACTOR_NAME = "extractor"
  val TEMPLATES_NAME = "templates"
  var loader: MappingLoader = new FileMappingLoader

  def fromJsonStr(jsonString: String): Mapping = {
    val m = read[Option[MappingRaw]](jsonString).map(new Mapping(_))
    m.getOrElse(throw new IllegalArgumentException(s"cant parse jsonString: $jsonString"))
  }

  def toJson(m: Mapping): String = {
    import org.json4s.JsonDSL._
    val mr = m.m
    val json =
      ("extractor" -> mr.extractor) ~
        ("templates" ->
          mr.templates.map {
            (urlt) => ("serviceName" -> urlt.serviceName) ~ ("template" -> urlt.template)
          })
    compact(json)
    //    write[MappingRaw](m.m)
  }

  def load(path:String): Future[Mapping] = Future {
    loader.load(path)
  }.map(fromJsonStr(_: String))

  implicit object Mapping extends JsonEventBody[Mapping] {
    val jsonMediaType = MediaType("application/vnd.blinkbox.books.ingestion.quartermaster.v2+json")
  }
}

case class MappingRaw(extractor: String, templates: List[UrlTemplate])

case class Mapping(m: MappingRaw) extends JsonMethods with v2.JsonSupport with Configuration {
  implicit val formats = DefaultFormats + FieldSerializer[Mapping]() + FieldSerializer[UrlTemplate]()
  implicit val timeout = AppConfig.timeout

  def store(mappingPath: String): Future[Unit] = Future {
    Mapping.loader.write(mappingPath, Mapping.toJson(this))
  }

  def broadcastUpdate(qsender: ActorRef, eventHeader: EventHeader): Future[Any] = {
    import akka.pattern.ask
    qsender ? Event.json[Mapping](eventHeader, this)
  }

  val jsonMediaType: MediaType = MediaType("application/vnd.blinkbox.books.ingestion.quartermaster.v2+json")
}
