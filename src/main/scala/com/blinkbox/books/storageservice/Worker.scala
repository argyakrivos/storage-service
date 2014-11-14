package com.blinkbox.books.storageservice

import java.nio.file.{FileSystems, Files, Path}

import com.blinkbox.books.spray.{Directives => CommonDirectives}
import spray.http.DateTime

import scala.collection.mutable.{HashMap, MultiMap}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

case class ProviderType(name: String)

case class JobId(providerType: ProviderType, assetToken: AssetDigest)

trait StorageDao {
  val rootPath: String

  def write(assetToken: AssetDigest, data: Array[Byte]): Future[Unit]

  def cleanUp(assetToken: AssetDigest): Future[Unit]
}

case class StorageProvider(repo: StorageProviderRepo, providerType: ProviderType, dao: StorageDao) {
  def isAssetWritable(status: Status) = status match {
    case Status.notFound | Status.finished | Status.failed => true
    case _ => false
  }

  def writeIfNotStarted(assetToken: AssetDigest, data: Array[Byte]): Future[(ProviderType, Status)] = {
    val jobId = JobId(providerType, assetToken)
    repo.getStatus(jobId).flatMap(status => isAssetWritable(status) match {
      case true => write(assetToken, data).recoverWith({ case _ => cleanUp(assetToken)})
      case _ => Future.successful(providerType, status)
    })
  }

  def write(assetToken: AssetDigest, data: Array[Byte]): Future[(ProviderType, Status)] = {
    val numBytes = data.length
    val started = DateTime.now
    val jobId = JobId(providerType, assetToken)
    for {
      _ <- repo.updateProgress(jobId, numBytes, started, 0)
      _ <- dao.write(assetToken, data)
      _ <- repo.removeProgress(jobId)
    } yield (providerType, Status.finished)
  }

  def cleanUp(assetToken: AssetDigest): Future[(ProviderType, Status)] =
    for {
      _ <- dao.cleanUp(assetToken)
      _ <- repo.removeProgress(JobId(providerType, assetToken))
    } yield (providerType, Status.failed)
}

 case class StorageManager(repo: StorageProviderRepo, providerConfigs: Set[ProviderConfig]) {
  val providerType = providerConfigs.map(_.provider.providerType)
  val label2Providers = getProviders(providerConfigs)

  private def toImmutableMap[A, B](x: collection.mutable.Map[A, collection.mutable.Set[B]]): Map[A, collection.immutable.Set[B]] =
    x.map((kv) => (kv._1, kv._2.toSet)).toMap

  private def getProviders(providerConfigs: Set[ProviderConfig]): Map[Label, Set[StorageProvider]] = {
    val tmpMultiMap = new HashMap[Label, collection.mutable.Set[StorageProvider]] with MultiMap[Label, StorageProvider]
    providerConfigs.map((dc) => dc.labels.map((label) => tmpMultiMap.addBinding(label, dc.provider)))
    toImmutableMap[Label, StorageProvider](tmpMultiMap)
  }

  def getProvidersForLabel(label: Label): Set[StorageProvider] = {
    label2Providers.getOrElse(label, Set.empty)
  }

  def storeAsset(assetToken: AssetDigest, data: Array[Byte], label: Label): Future[Map[ProviderType, Status]] = {
    val storageProviders = getProvidersForLabel(label)
     Future.traverse[StorageProvider, (ProviderType, Status), Set](storageProviders)(_.writeIfNotStarted(assetToken, data))
     .map{ (s) =>  s.toMap}
  }

  def getStatus(assetToken: AssetDigest): Future[Map[ProviderType, Status]] =
    Future.traverse(providerType)((dt) =>
      repo.getStatus(JobId(dt, assetToken)).map((dt, _))).map(_.toMap)

  def getProgress(assetToken: AssetDigest): Future[Map[ProviderType, Option[Progress]]] =
    Future.traverse(providerType)((dt) =>
      repo.getProgress(JobId(dt, assetToken)).map((dt, _))).map(_.toMap)

  def cleanUp(assetToken: AssetDigest, label: Label): Future[Set[(ProviderType, Status)]] =
    Future.traverse(getProvidersForLabel(label))(_.cleanUp(assetToken))
}

case class LocalStorageDao(rootPath: String) extends StorageDao {
  def getPath(assetToken: AssetDigest): Path = FileSystems.getDefault.getPath(rootPath, assetToken.toFileString())

  override def write(assetToken: AssetDigest, data: Array[Byte]): Future[Unit] = Future(Files.write(getPath(assetToken), data))

  override def cleanUp(assetToken: AssetDigest): Future[Unit] = Future(Files.deleteIfExists(getPath(assetToken))).map(_ => ())
}