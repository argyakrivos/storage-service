package com.blinkbox.books.storageservice

import java.nio.file.{FileSystems, Files, Path}
import java.util.concurrent.atomic.AtomicReference
import com.blinkbox.books
import com.blinkbox.books.config
import com.blinkbox.books.spray.{Directives => CommonDirectives}
import shapeless.get
import spray.http.DateTime
import com.typesafe.config.Config
import scala.collection.mutable.{HashMap, MultiMap}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

case class ProviderId(name: String)

case class JobId(providerId: ProviderId, assetDigest: AssetDigest)

abstract class StorageDao {
  val storageConfig:NamedStorageConfig
  val providerId: ProviderId = storageConfig.providerId

  def write(assetDigest: AssetDigest, data: Array[Byte]): Future[Unit]
  def cleanUp(assetDigest: AssetDigest): Future[Unit]
  def exists(assetDigest:AssetDigest): Future[Boolean]
}

case class StorageProvider(repo: StorageProviderRepo,  dao: StorageDao) {
  val providerId = dao.providerId
  def isAssetWritable(status: Status) = status match {
    case Status.notFound | Status.finished | Status.failed => true
    case _ => false
  }

  def writeIfNotStarted(assetDigest: AssetDigest, data: Array[Byte]): Future[(ProviderId, Status)] = {
    val jobId = JobId(providerId, assetDigest)
    repo.getStatus(jobId).flatMap(status => isAssetWritable(status) match {
      case true => write(assetDigest, data).recoverWith({ case e => cleanUp(assetDigest)})
      case _ => Future.successful(providerId, status)
    })
  }

  def write(assetDigest: AssetDigest, data: Array[Byte]): Future[(ProviderId, Status)] = {
    val numBytes = data.length
    val started = DateTime.now
    val jobId = JobId(providerId, assetDigest)
    for {
      _ <- repo.updateProgress(jobId, numBytes, started, 0)
      _ <- dao.write(assetDigest, data)
      _ <- repo.removeProgress(jobId)
    } yield (providerId, Status.finished)
  }

  def cleanUp(assetDigest: AssetDigest): Future[(ProviderId, Status)] =
    for {
      _ <- dao.cleanUp(assetDigest)
      _ <- repo.removeProgress(JobId(providerId, assetDigest))
    } yield (providerId, Status.failed)
}

 case class StorageManager(repo: StorageProviderRepo, initMapping: Mapping, storageProviders:Set[StorageProvider]) {
   val mapping= new AtomicReference(initMapping)

   def getProvidersFor(fn:(UrlTemplate)=>Boolean):Set[StorageProvider] = (for {
     template <- mapping.get.templates
     if fn(template)
     storageProvider <- storageProviders
     if storageProvider.providerId == template.providerId
   } yield storageProvider).toSet

    def getProvidersForLabel(label: Label):Set[StorageProvider] = getProvidersFor(_.label == label)
    def getProvidersForDigest(digest: AssetDigest):Set[StorageProvider] = getProvidersFor(_.matches(digest))

    def storeAsset(label: Label, data: Array[Byte]): (AssetDigest, Future[Map[ProviderId, Status]]) = {
    val ad = GenAssetDigest(data, label)
    val f = Future.traverse[StorageProvider, (ProviderId, Status), Set](getProvidersForLabel(label))(_.writeIfNotStarted(ad, data))
      .map { (s) => s.toMap}
    (ad,f)
  }

  def getStatus(assetDigest: AssetDigest): Future[Map[ProviderId, Status]] =
    Future.traverse(getProvidersForDigest(assetDigest))((dt) =>
      repo.getStatus(JobId(dt.providerId, assetDigest)).map((dt.providerId, _))).map(_.toMap)

  def getProgress(assetDigest: AssetDigest): Future[Map[ProviderId, Option[Progress]]] =
    Future.traverse(getProvidersForDigest(assetDigest))((dt) =>
      repo.getProgress(JobId(dt.providerId, assetDigest)).map((dt.providerId, _))).map(_.toMap)

  def cleanUp(assetDigest: AssetDigest): Future[Set[(ProviderId, Status)]] =
    Future.traverse(getProvidersForDigest(assetDigest))(_.cleanUp(assetDigest))
}

case class LocalStorageDao(storageConfig: LocalStorageConfig) extends StorageDao {
  def getPath(assetDigest: AssetDigest): Path = FileSystems.getDefault.getPath(storageConfig.localStoragePath, assetDigest.toFileString())

  override def write(assetDigest: AssetDigest, data: Array[Byte]): Future[Unit] = Future(Files.write(getPath(assetDigest), data))

  override def cleanUp(assetDigest: AssetDigest): Future[Unit] = Future(Files.deleteIfExists(getPath(assetDigest))).map(_ => ())

  override def exists(assetDigest: AssetDigest): Future[Boolean] = Future(Files.exists(getPath(assetDigest)))
}