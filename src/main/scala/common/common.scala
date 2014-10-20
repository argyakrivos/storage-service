package common

import spray.http.DateTime

/**
 * Created by greg on 16/10/14.
 */
case class Status (eta:DateTime,  available:Boolean)
case class AssetData(timeStarted:DateTime, totalSize:Long)
case class Progress(assetData:AssetData, sizeWritten:Long )
case class UserId(id:String)
case class UrlTemplate(serviceName:String, template:String)
//TODO rename to mapping val
object Status  extends Ordering[Status]{

  val neverStatus:Status = new Status(DateTime.MaxValue, false)
  def isDone(progress:Progress):Boolean = progress.sizeWritten >= progress.assetData.totalSize

  override def compare(a: Status,b: Status): Int = a.eta.clicks compare b.eta.clicks

  def earlierStatus(latestProgress:Progress, earliestStatus:Status):Status = {
    val that = toStatus(latestProgress)
    min (earliestStatus, that)
  }

  def toStatus(progress:Progress):Status = {
    val now = DateTime.now
    if (isDone(progress))
      return new Status(now, true)
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
  //TODO complete the status calcualtion and serialise
  def getStatus(progress:List[Progress]):Status = progress.foldRight[Status](neverStatus)(earlierStatus)
  //various f
}

case class AssetToken(token:String)

case class StorageRequest(data:Array[Byte], label : Int )

case class StorageWorkerRequest(assetToken: AssetToken, storageRequest : StorageRequest)