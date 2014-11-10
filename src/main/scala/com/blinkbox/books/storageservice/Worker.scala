package com.blinkbox.books.storageservice

import java.nio.file.{FileSystems, Files, Path}

import com.blinkbox.books.spray.{Directives => CommonDirectives}
import spray.http.DateTime

import scala.collection.mutable.{HashMap, MultiMap}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

case class DelegateType(name: String)

case class JobId(delegateType: DelegateType, assetToken: AssetToken)

trait StorageDao {
  val rootPath: String

  def write(assetToken: AssetToken, data: Array[Byte]): Future[Unit]

  def cleanUp(assetToken: AssetToken): Future[Unit]
}

case class StorageDelegate(repo: StorageWorkerRepo, delegateType: DelegateType, dao: StorageDao) {
  def isAssetWritable(status: Status) = status match {
    case Status.notFound | Status.finished | Status.failed => true
    case _ => false
  }

  def writeIfNotStarted(assetToken: AssetToken, data: Array[Byte]): Future[(DelegateType, Status)] = {
    val jobId = JobId(delegateType, assetToken)
    repo.getStatus(jobId).flatMap(status => isAssetWritable(status) match {
      case true => write(assetToken, data).recoverWith({ case _ => cleanUp(assetToken)})
      case _ => Future.successful(delegateType, status)
    })
  }

  def write(assetToken: AssetToken, data: Array[Byte]): Future[(DelegateType, Status)] = {
    val numBytes = data.length
    val started = DateTime.now
    val jobId = JobId(delegateType, assetToken)
    for {
      _ <- repo.updateProgress(jobId, numBytes, started, 0)
      _ <- dao.write(assetToken, data)
      _ <- repo.removeProgress(jobId)
    } yield (delegateType, Status.finished)
  }

  def cleanUp(assetToken: AssetToken): Future[(DelegateType, Status)] =
    for {
      _ <- dao.cleanUp(assetToken)
      _ <- repo.removeProgress(JobId(delegateType, assetToken))
    } yield (delegateType, Status.failed)
}

 case class StorageManager(repo: StorageWorkerRepo, delegateConfigs: Set[DelegateConfig]) {
  val delegateTypes = delegateConfigs.map(_.delegate.delegateType)
  val label2Delegates = getDelegates(delegateConfigs)

  private def toImmutableMap[A, B](x: collection.mutable.Map[A, collection.mutable.Set[B]]): Map[A, collection.immutable.Set[B]] =
    x.map((kv) => (kv._1, kv._2.toSet)).toMap

  private def getDelegates(delegateConfigs: Set[DelegateConfig]): Map[Label, Set[StorageDelegate]] = {
    val tmpMultiMap = new HashMap[Label, collection.mutable.Set[StorageDelegate]] with MultiMap[Label, StorageDelegate]
    delegateConfigs.map((dc) => dc.labels.map((label) => tmpMultiMap.addBinding(label, dc.delegate)))
    toImmutableMap[Label, StorageDelegate](tmpMultiMap)
  }

  def getDelegatesForLabel(label: Label): Set[StorageDelegate] = {
    label2Delegates.getOrElse(label, Set.empty)
  }

  def storeAsset(assetToken: AssetToken, data: Array[Byte], label: Label): Future[Map[DelegateType, Status]] = {
    val storageDelegates = getDelegatesForLabel(label)
     Future.traverse[StorageDelegate, (DelegateType, Status), Set](storageDelegates)(_.writeIfNotStarted(assetToken, data))
     .map{ (s) =>  s.toMap}
  }

  def getStatus(assetToken: AssetToken): Future[Map[DelegateType, Status]] =
    Future.traverse(delegateTypes)((dt) =>
      repo.getStatus(JobId(dt, assetToken)).map((dt, _))).map(_.toMap)

  def getProgress(assetToken: AssetToken): Future[Map[DelegateType, Option[Progress]]] =
    Future.traverse(delegateTypes)((dt) =>
      repo.getProgress(JobId(dt, assetToken)).map((dt, _))).map(_.toMap)

  def cleanUp(assetToken: AssetToken, label: Label): Future[Set[(DelegateType, Status)]] =
    Future.traverse(getDelegatesForLabel(label))(_.cleanUp(assetToken))
}

case class LocalStorageDao(rootPath: String) extends StorageDao {
  def getPath(assetToken: AssetToken): Path = FileSystems.getDefault.getPath(rootPath, assetToken.toFileString())

  override def write(assetToken: AssetToken, data: Array[Byte]): Future[Unit] = Future(Files.write(getPath(assetToken), data))

  override def cleanUp(assetToken: AssetToken): Future[Unit] = Future(Files.deleteIfExists(getPath(assetToken))).map(_ => ())
}