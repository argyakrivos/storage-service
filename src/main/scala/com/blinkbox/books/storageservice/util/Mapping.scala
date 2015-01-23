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

abstract class Store(val label: String, val extractor: String, val providers: Map[String,String]) {
  def createToken(path: String): Token
  def write(bytes: Array[Byte], path: String): Future[Token]
  def uploadStatusUpdate(token: Token): Option[ProviderStatus]
}

case class LocalStorageStore(localStorageConfig: NamedStorageConfig, override val label: String, override val extractor: String, override val providers: Map[String, String]) extends Store(label, extractor, providers) {

  val rootPath = localStorageConfig.storagePath

  override def createToken(path: String): Token = Token(s"""bbbmap:testfile:file://$path""")

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
      val root = path.getFileSystem.getPath(rootPath)

      if (isValidPath(path, root)) {
        Some(ProviderStatus(token, label, Map("available" -> true)))
      } else {
        None
      }
    }
  }

  def isValidPath(path: Path, root: Path): Boolean = path.startsWith(root) && Files.exists(path) && Files.isReadable(path)
}

case class CommonMapping(label: String, extractor: String, providers: Map[String, String])

trait StoreMappingUtils extends v2.JsonSupport {
  val appConfig: AppConfig

  def mappings(mappingFileLocation: String): Array[CommonMapping] =
    read[Array[CommonMapping]](Source.fromFile(mappingFileLocation).mkString)

  def mappingsToDao(mappings:Array[CommonMapping]): Array[Store] = {
    def labelToDao(mapping: CommonMapping): Store = mapping.label match {
      case "testfile" => new LocalStorageStore(appConfig.localStorageConfig, mapping.label, mapping.extractor, mapping.providers)
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
