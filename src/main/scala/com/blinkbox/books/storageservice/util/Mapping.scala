package com.blinkbox.books.storageservice.util

import java.nio.file.{StandardOpenOption, Files, Paths, Path}

import com.blinkbox.books.spray.v2
import com.blinkbox.books.storageservice.{ProviderStatus, NamedStorageConfig, LocalStorageConfig, AppConfig}
import org.json4s._
import org.json4s.jackson.JsonMethods._
import org.json4s.jackson.Serialization.read

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.io.Source

sealed abstract class UploadStatus(val progress: Integer, val eta: Integer)
case object NotStarted extends UploadStatus(0, 0)
case object Finished extends UploadStatus(100, 0)

abstract class Dao(val label: String, val extractor: String, val providers: Map[String,String]) {
  def createToken(path: String): Token
  def write(bytes: Array[Byte], path: String): Future[Token]
  def uploadStatusUpdate(token: Token): Option[ProviderStatus]
}

class LocalStorageDao(localStorageConfig: NamedStorageConfig, label: String, extractor: String, providers: Map[String, String]) extends Dao(label, extractor, providers) {

  val rootPath = localStorageConfig.storagePath

  override def createToken(path: String): Token = Token(s"""bbbmap:testfile:file:/$path""")

  override def write(bytes: Array[Byte], path: String): Future[Token] = write(bytes, Paths.get(path))

  def write(bytes: Array[Byte], path: Path): Future[Token] = Future {
    assert(!path.isAbsolute)
    val root = path.getFileSystem.getPath(rootPath)
    val absolutePath = root.resolve(path)

    Files.createDirectories(absolutePath.getParent)
    Files.write(absolutePath, bytes, StandardOpenOption.CREATE)
    createToken(absolutePath.toString)
  }

  def uploadStatusUpdate(token: Token): Option[ProviderStatus] = {
    if(!token.token.startsWith("bbbmap:testfile")){
      None
    } else {
      val path = token.getFilePath
      if (Files.exists(path) && Files.isReadable(path)) {
        Some(ProviderStatus(token, label, Map("available" -> true)))
      } else {
        None
      }
    }
  }
}

case class CommonMapping(label: String, extractor: String, providers: Map[String, String])

trait DaoMappingUtils extends v2.JsonSupport {
  val appConfig: AppConfig

  def mappings(mappingFileLocation: String): Array[CommonMapping] =
    read[Array[CommonMapping]](Source.fromFile(mappingFileLocation).mkString)

  def mappingsToDao(mappings:Array[CommonMapping]): Array[Dao] = {
    def labelToDao(mapping: CommonMapping): Dao = mapping.label match {
      case "testfile" => new LocalStorageDao(appConfig.localStorageConfig, mapping.label, mapping.extractor, mapping.providers)
      case _ => throw new UnsupportedOperationException(s"The label ${mapping.label} is currently not supported by the StorageService")
    }

    mappings.map(labelToDao)
  }
}

case class Token(token: String) {
  require(token.matches("[a-zA-Z]+:[a-zA-Z]+:.+"))
  def getFileLocation: String = token.replaceAll(".+:.+:", "")
  def getFilePath: Path = Paths.get(getFileLocation)
}
