package com.blinkbox.books.storageservice

import java.nio.file.{FileSystems, Files, Path}
import java.util.concurrent.atomic.AtomicReference
import com.blinkbox.books
import com.blinkbox.books.config
import com.blinkbox.books.spray.{Directives => CommonDirectives}
import spray.http.DateTime
import com.typesafe.config.Config
import scala.collection.mutable.{HashMap, MultiMap}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

case class ServiceName(name: String)

case class JobId(serviceName: ServiceName, assetDigest: AssetDigest)

abstract class StorageDao(sc:NamedConfig) {
  val serviceName: ServiceName = ServiceName(sc.serviceName)

  def write(assetDigest: AssetDigest, data: Array[Byte]): Future[Unit]

  def cleanUp(assetDigest: AssetDigest): Future[Unit]
}

case class StorageProvider(repo: StorageProviderRepo,  dao: StorageDao) {
  val serviceName = dao.serviceName
  def isAssetWritable(status: Status) = status match {
    case Status.notFound | Status.finished | Status.failed => true
    case _ => false
  }

  def writeIfNotStarted(assetDigest: AssetDigest, data: Array[Byte]): Future[(ServiceName, Status)] = {
    val jobId = JobId(serviceName, assetDigest)
    repo.getStatus(jobId).flatMap(status => isAssetWritable(status) match {
      case true => write(assetDigest, data).recoverWith({ case _ => cleanUp(assetDigest)})
      case _ => Future.successful(serviceName, status)
    })
  }

  def write(assetDigest: AssetDigest, data: Array[Byte]): Future[(ServiceName, Status)] = {
    val numBytes = data.length
    val started = DateTime.now
    val jobId = JobId(serviceName, assetDigest)
    for {
      _ <- repo.updateProgress(jobId, numBytes, started, 0)
      _ <- dao.write(assetDigest, data)
      _ <- repo.removeProgress(jobId)
    } yield (serviceName, Status.finished)
  }

  def cleanUp(assetDigest: AssetDigest): Future[(ServiceName, Status)] =
    for {
      _ <- dao.cleanUp(assetDigest)
      _ <- repo.removeProgress(JobId(serviceName, assetDigest))
    } yield (serviceName, Status.failed)
}

 case class StorageManager(repo: StorageProviderRepo, initMapping: Mapping, storageProviders:Set[StorageProvider]) {
   val mapping= new AtomicReference(initMapping)

   def getProvidersForLabel(label: Label):Set[StorageProvider] = {
     val serviceNames = mapping.get.templates.filter(_.label == label).map(_.serviceName)
     storageProviders.filter(sp => serviceNames.contains(sp.serviceName))
   }

   def getProvidersForDigest(digest: AssetDigest):Set[StorageProvider] = {
     val serviceNames = mapping.get.templates.filter(_.matches(digest)).map(_.serviceName)
     storageProviders.filter(sp => serviceNames.contains(sp.serviceName))
   }

  def storeAsset(label: Label, data: Array[Byte]): (AssetDigest, Future[Map[ServiceName, Status]]) = {
    val ad = GenAssetDigest(data, label)
    val f = Future.traverse[StorageProvider, (ServiceName, Status), Set](getProvidersForLabel(label))(_.writeIfNotStarted(ad, data))
      .map { (s) => s.toMap}
    (ad,f)
  }

  def getStatus(assetDigest: AssetDigest): Future[Map[ServiceName, Status]] =
    Future.traverse(getProvidersForDigest(assetDigest))((dt) =>
      repo.getStatus(JobId(dt.serviceName, assetDigest)).map((dt.serviceName, _))).map(_.toMap)

  def getProgress(assetDigest: AssetDigest): Future[Map[ServiceName, Option[Progress]]] =
    Future.traverse(getProvidersForDigest(assetDigest))((dt) =>
      repo.getProgress(JobId(dt.serviceName, assetDigest)).map((dt.serviceName, _))).map(_.toMap)

  def cleanUp(assetDigest: AssetDigest): Future[Set[(ServiceName, Status)]] =
    Future.traverse(getProvidersForDigest(assetDigest))(_.cleanUp(assetDigest))
}

case class LocalStorageDao(c: LocalStorageConfig) extends StorageDao(c:NamedConfig) {
  def getPath(assetDigest: AssetDigest): Path = FileSystems.getDefault.getPath(c.localStoragePath, assetDigest.toFileString())

  override def write(assetDigest: AssetDigest, data: Array[Byte]): Future[Unit] = Future(Files.write(getPath(assetDigest), data))

  override def cleanUp(assetDigest: AssetDigest): Future[Unit] = Future(Files.deleteIfExists(getPath(assetDigest))).map(_ => ())
}