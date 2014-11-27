package com.blinkbox.books.storageservice

import java.nio.file.{FileSystems, Files, Path}
import java.util.concurrent.atomic.AtomicReference
import com.blinkbox.books.spray.{Directives => CommonDirectives}
import spray.http.DateTime
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

case class ProviderId(name: String)
case class JobId(providerId: String, assetDigest: AssetDigest)

abstract class StorageDao {
  val storageConfig:NamedStorageConfig
  val providerId = storageConfig.providerId
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

  def writeIfNotStarted(assetDigest: AssetDigest, data: Array[Byte]): Future[(String, Status)] = {
    val jobId = JobId(providerId, assetDigest)
    repo.getStatus(jobId).flatMap(status => isAssetWritable(status) match {
      case true => write(assetDigest, data).recoverWith({ case e => cleanUp(assetDigest)})
      case _ => Future.successful(providerId, status)
    })
  }

  def write(assetDigest: AssetDigest, data: Array[Byte]): Future[(String, Status)] = {
    val numBytes = data.length
    val started = DateTime.now
    val jobId = JobId(providerId, assetDigest)
    for {
      _ <- repo.updateProgress(jobId, numBytes, started, 0)
      _ <- dao.write(assetDigest, data)
      _ <- repo.removeProgress(jobId)
    } yield (providerId, Status.finished)
  }

  def cleanUp(assetDigest: AssetDigest): Future[(String, Status)] =
    for {
      _ <- dao.cleanUp(assetDigest)
      _ <- repo.removeProgress(JobId(providerId, assetDigest))
    } yield (providerId, Status.failed)
}

 case class StorageManager(repo: StorageProviderRepo, initMapping: Mapping, storageProviders:Set[StorageProvider]) {
   val mapping= new AtomicReference(initMapping)
   def getProvidersForLabel(label: String):Set[StorageProvider] = getProvidersFor(_.label == label)
   def getProvidersForDigest(digest: AssetDigest):Set[StorageProvider] = getProvidersFor(_.matches(digest))

   def getProvidersFor(filterFunction:(ProviderConfig)=>Boolean): Set[StorageProvider] = {
     val providers = for {
       template <- mapping.get.providers
       if filterFunction(template)
       storageProvider <- storageProviders
       providerIdTemplate <- template.providers
       if storageProvider.providerId == providerIdTemplate._1
      } yield storageProvider
     providers.toSet
   }

  def storeAsset(label: String, data: Array[Byte]): (AssetDigest, Future[Map[String, Status]]) = {
    val assetDigest = GenAssetDigest(data, label)
    val eventualStatusMap = Future.traverse[StorageProvider, (String, Status), Set](getProvidersForLabel(label))(_.writeIfNotStarted(assetDigest, data))
    (assetDigest,eventualStatusMap.map {(statusTuples) => statusTuples.toMap})
  }

  def getStatus(assetDigest: AssetDigest): Future[Map[String, Status]] =
    Future.traverse(getProvidersForDigest(assetDigest))((dt) =>
      repo.getStatus(JobId(dt.providerId, assetDigest)).map((dt.providerId, _))).map(_.toMap)

  def getProgress(assetDigest: AssetDigest): Future[Map[String, Option[Progress]]] =
    Future.traverse(getProvidersForDigest(assetDigest))((dt) =>
      repo.getProgress(JobId(dt.providerId, assetDigest)).map((dt.providerId, _))).map(_.toMap)

  def cleanUp(assetDigest: AssetDigest): Future[Set[(String, Status)]] =
    Future.traverse(getProvidersForDigest(assetDigest))(_.cleanUp(assetDigest))
}

case class LocalStorageDao(storageConfig: LocalStorageConfig) extends StorageDao {
  def getPath(assetDigest: AssetDigest): Path = FileSystems.getDefault.getPath(storageConfig.storagePath, assetDigest.toFileString())
  override def write(assetDigest: AssetDigest, data: Array[Byte]): Future[Unit] = Future(Files.write(getPath(assetDigest), data))
  override def cleanUp(assetDigest: AssetDigest): Future[Unit] = Future(Files.deleteIfExists(getPath(assetDigest))).map(_ => ())
  override def exists(assetDigest: AssetDigest): Future[Boolean] = Future(Files.exists(getPath(assetDigest)))
}