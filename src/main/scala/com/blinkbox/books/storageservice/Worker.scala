package com.blinkbox.books.storageservice

import java.nio.file.{FileSystems, Path}

import com.blinkbox.books.spray.{Directives => CommonDirectives}
import spray.http.DateTime
import spray.util.NotImplementedException
import scala.collection.concurrent.TrieMap
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future


trait StorageService {
  def storeAsset(token: AssetToken, data: Array[Byte], label: Int): Future[Map[DelegateType, Status]]
  def getProgress(token: AssetToken): Future[Map[DelegateType, Progress]]
  def getStatus(assetToken: AssetToken): Future[Map[DelegateType, Status]]
  def cleanUp(assetToken: AssetToken, label: Int): Future[Set[(DelegateType, Status)]]
}

trait StorageDelegate {
  val repo: TrieMap[DelegateKey, Progress]
  val delegateType: DelegateType

  def getStatus(token: AssetToken): Option[Status] =
    repo.get(new DelegateKey(delegateType, token)) map (Status.toStatus(_))

  def genToken(data: Array[Byte]): AssetToken = new AssetToken(data.hashCode.toString)

  def storeProgress = repo.put _

  def getProgress(token: AssetToken): Progress = repo.get(new DelegateKey(delegateType, token)).get

  def removeProgress(token: AssetToken) =
    repo.remove(new DelegateKey(delegateType, token))

  def updateProgress(token: AssetToken, size: Long, started: DateTime, bytesWritten: Long) =
    repo.putIfAbsent(new DelegateKey(delegateType, token), new Progress(new AssetData(started, size), bytesWritten))

  def write(assetToken: AssetToken, data: Array[Byte]): Future[(DelegateType, Status)]

  def cleanUp(assetToken: AssetToken): Future[(DelegateType, Status)]
}

case class QuarterMasterStorageWorker(swConfig: StorageWorkerConfig) extends StorageService {
  val delegates = swConfig.delegates
  val delegateTypes = swConfig.delegateTypes
  val repo = AppConfig.repo

  private def getDelegates(label: Int): Set[StorageDelegate] = {
    val maybeDelegates: Option[Set[StorageDelegate]] = delegates.get(label)
    val size = maybeDelegates.map(_.size).getOrElse(0)
    maybeDelegates.getOrElse(Set.empty)
  }

  override def storeAsset(assetToken: AssetToken, data: Array[Byte], label: Int): Future[Map[DelegateType, Status]] = {
    val storageDelegates: Set[StorageDelegate] = getDelegates(label)
    if (storageDelegates.size<swConfig.minStorageDelegates){
      throw new NotImplementedException(s" label $label is has no available storage delegates")
    }
    Future.traverse[StorageDelegate, (DelegateType, Status), Set](storageDelegates)((sd: StorageDelegate) => {
      sd.write(assetToken, data)
    }).recoverWith({ case _ => cleanUp(assetToken, label)}).map(_.toMap)
  }

  def makeMap[A](s:Set[Option[(DelegateType, A)]]):Map[DelegateType, A] = s.flatten.toMap

  override def getStatus(assetToken: AssetToken): Future[Map[DelegateType, Status]] =
    Future.traverse(delegateTypes)((dt: DelegateType) =>
      Future {repo.get(new DelegateKey(dt, assetToken)).map((p: Progress) => (dt, Status.toStatus(p)))}).map(makeMap(_))


  override def getProgress(assetToken: AssetToken): Future[Map[DelegateType, Progress]] =
    Future.traverse(delegateTypes)((dt: DelegateType) => Future {
        repo.get(new DelegateKey(dt, assetToken)).map((dt, _))
      }).map(makeMap _)

  override def cleanUp(assetToken: AssetToken, label: Int): Future[Set[(DelegateType, Status)]] =
    Future.traverse(getDelegates(label))(_.cleanUp(assetToken))
}

case class LocalStorageDelegate(repo: TrieMap[DelegateKey, Progress], path: String, delegateType: DelegateType) extends StorageDelegate {

  def getPath(assetToken: AssetToken): Path = FileSystems.getDefault.getPath(path, assetToken.toFileString)

  override def write(assetToken: AssetToken, data: Array[Byte]): Future[(DelegateType, Status)] = Future {
    val numBytes: Long = data.length
    val started: DateTime = DateTime.now
    updateProgress(assetToken, numBytes, started, 0)
    java.nio.file.Files.write(getPath(assetToken), data)
    val status = Status.toStatus(this.getProgress(assetToken))
    removeProgress(assetToken)
    (delegateType, status)
  }

  override def cleanUp(assetToken: AssetToken): Future[(DelegateType, Status)] =
    Future {
      java.nio.file.Files.deleteIfExists(getPath(assetToken))
      removeProgress(assetToken)
      (delegateType, Status.neverStatus)
    }
}


