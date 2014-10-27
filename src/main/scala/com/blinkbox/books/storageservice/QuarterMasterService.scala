package com.blinkbox.books.storageservice

import com.blinkbox.books.spray.{Directives => CommonDirectives}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.concurrent.{Await, Future}

case class QuarterMasterService(appConfig: AppConfig) {
  val storageWorker = new QuarterMasterStorageWorker(appConfig.swc)
  var mapping: Mapping = Await.result(Mapping.load(appConfig.mappingpath), 1000 millis)

 def cleanUp(assetToken: AssetToken, label: Int): Future[Map[DelegateType, Status]] =
   storageWorker.cleanUp(assetToken, label).map((_.toMap))

  def storeAsset(bytes: Array[Byte], label: Int): Future[(AssetToken, Future[Map[DelegateType, Status]])] = Future {
    val assetToken = genToken(bytes)
    val f: Future[Map[DelegateType, Status]] = storageWorker.storeAsset(assetToken, bytes, label)
    (assetToken, f)
  }

  def getStatus(token: AssetToken): Future[Map[DelegateType, Status]] =
    storageWorker.getStatus(token)

  def genToken(data: Array[Byte]): AssetToken = new AssetToken(data.hashCode.toString)

  def _updateAndBroadcastMapping(mappingStr: String): Future[String ] =
    (for {
      mapping <- Future{Mapping.fromJsonStr(mappingStr)}
      _ <- mapping.store(appConfig.mappingpath)
      _ <- mapping.broadcastUpdate(appConfig.rmq.qSender, appConfig.eventHeader)
    } yield mapping).recover { case _ => this.mapping }.map { Mapping.toJson(_) }

  def loadMapping(): Future[String] =
    Mapping.load(appConfig.mappingpath).map((loaded: Mapping) => {
      mapping = loaded
      mapping
    }).recover[Mapping] {
      case _ => mapping
    }.map((Mapping.toJson(_)))

}









