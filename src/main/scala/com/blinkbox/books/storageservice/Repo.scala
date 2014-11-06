package com.blinkbox.books.storageservice

import spray.http.DateTime

import scala.collection.concurrent.TrieMap
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

case class Status(eta: DateTime, available: Boolean)

case class Progress(assetData: AssetData, sizeWritten: Long)

object Status extends Ordering[Status] {
  val failed = new Status(DateTime.MaxValue, false)
  val notFound = new Status(DateTime.MinValue, false)
  val finished = new Status(DateTime.MinValue, true)

  override def compare(a: Status, b: Status): Int = a.eta.clicks compare b.eta.clicks

  def isDone(progress: Progress): Boolean = progress.sizeWritten >= progress.assetData.totalSize

  def earlierStatus(latestProgress: Progress, earliestStatus: Status): Status =
    min(earliestStatus, toStatus(latestProgress))

  def toStatus(progress: Progress): Status = {
    val now = DateTime.now
    if (isDone(progress))
      new Status(now, true)
    else {
      val size = progress.assetData.totalSize
      val written = progress.sizeWritten
      val start = progress.assetData.timeStarted
      val unwritten = size - written
      val timeTakenMillis = now.clicks - start.clicks
      val bytesPerMillis = written / timeTakenMillis
      val etaClicks = unwritten / bytesPerMillis
      new Status(now + etaClicks, false)
    }
  }

  def getStatus(progress: List[Progress], name: DelegateType): Status = progress.foldRight[Status](failed)(earlierStatus)
}

trait StorageWorkerRepo {
  def storeProgress(jobId: JobId, progress: Progress): Future[Unit]

  def getProgress(jobId: JobId): Future[Option[Progress]]

  def getStatus(jobId: JobId): Future[Status]

  def removeProgress(jobId: JobId): Future[Unit]

  def updateProgress(jobId: JobId, size: Long, started: DateTime, bytesWritten: Long): Future[Unit]
}

class InMemoryRepo extends StorageWorkerRepo {
  val repo = new TrieMap[JobId, Progress]

  override def storeProgress(jobId: JobId, progress: Progress) =
    Future(repo.put _)

  override def getProgress(jobId: JobId) =
    Future(repo.get(jobId))

  override def getStatus(jobId: JobId) =
    Future(repo.get(jobId).map(Status.toStatus).getOrElse(Status.notFound))

  override def removeProgress(jobId: JobId) =
    Future(repo.remove(jobId))

  override def updateProgress(jobId: JobId, size: Long, started: DateTime, bytesWritten: Long) =
    Future(repo.putIfAbsent(jobId, new Progress(new AssetData(started, size), bytesWritten)))
}