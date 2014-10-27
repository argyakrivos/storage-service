package com.blinkbox.books.storageservice

import com.blinkbox.books.json.DefaultFormats
import org.json4s.FieldSerializer
import spray.http.DateTime

case class Status (eta:DateTime,  available:Boolean)
case class AssetData(timeStarted:DateTime, totalSize:Long)
case class Progress(assetData:AssetData, sizeWritten:Long )
case class UserId(id:String)
case class UrlTemplate(serviceName:String, template:String){
  implicit val formats = DefaultFormats + FieldSerializer[UrlTemplate]()
}
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

  def getStatus(progress:List[Progress], name:DelegateType):Status = progress.foldRight[Status](neverStatus)(earlierStatus)
}

case class AssetToken(token:String) {
  def toFileString():String = token
}

case class DelegateType(name:String)

case class DelegateKey(delegateType:DelegateType, assetToken:AssetToken)


