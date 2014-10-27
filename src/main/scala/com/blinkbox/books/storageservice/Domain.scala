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

object MappingModel extends JsonMethods with v2.JsonSupport  {
  implicit val formats = DefaultFormats + FieldSerializer[MappingModel]() + FieldSerializer[UrlTemplate]()
  val EXTRACTOR_NAME = "extractor"
  val TEMPLATES_NAME = "templates"
  var loader: MappingLoader = new FileMappingLoader

  def fromJsonStr(jsonString: String): MappingModel = {
    val m = read[Option[MappingValue]](jsonString).map(new MappingModel(_))
    m.getOrElse(throw new IllegalArgumentException(s"cant parse jsonString: $jsonString"))
  }

  def toJson(m: MappingModel): String = {
    import org.json4s.JsonDSL._
    val mr = m.m
    val json =
      ("extractor" -> mr.extractor) ~
        ("templates" ->
          mr.templates.map {
            (urlt) => ("serviceName" -> urlt.serviceName) ~ ("template" -> urlt.template)
          })
    compact(json)
  }

  def load(path:String): Future[MappingModel] = Future {
    loader.load(path)
  }.map(fromJsonStr(_: String))

  implicit object Mapping extends JsonEventBody[MappingModel] {
    val jsonMediaType = MediaType("application/vnd.blinkbox.books.ingestion.quartermaster.v2+json")
  }
}

case class MappingValue(extractor: String, templates: List[UrlTemplate])

case class MappingModel(m: MappingValue) extends JsonMethods with v2.JsonSupport with Configuration {
  implicit val formats = DefaultFormats + FieldSerializer[MappingModel]() + FieldSerializer[UrlTemplate]()
  implicit val timeout = AppConfig.timeout

  def store(mappingPath: String): Future[Unit] = Future {
    MappingModel.loader.write(mappingPath, MappingModel.toJson(this))
  }

  def broadcastUpdate(qsender: ActorRef, eventHeader: EventHeader): Future[Any] = {
    import akka.pattern.ask
    qsender ? Event.json[MappingModel](eventHeader, this)
  }

  val jsonMediaType: MediaType = MediaType("application/vnd.blinkbox.books.ingestion.quartermaster.v2+json")
}
