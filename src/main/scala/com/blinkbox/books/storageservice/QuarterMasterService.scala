package com.blinkbox.books.storageservice

import com.blinkbox.books.spray.{Directives => CommonDirectives}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.concurrent.{Await, Future}

case class QuarterMasterService(appConfig: AppConfig) {
  val storageWorker = new QuarterMasterStorageWorker(appConfig.swc)
  var mapping: MappingModel = Await.result(MappingModel.load(appConfig.mappingpath), 1000 millis)

 def cleanUp(assetToken: AssetToken, label: Int): Future[Map[DelegateType, Status]] =
   storageWorker.cleanUp(assetToken, label).map(_.toMap)

  def storeAsset(bytes: Array[Byte], label: Int): Future[(AssetToken, Future[Map[DelegateType, Status]])] = Future {
    val assetToken = genToken(bytes)
    val f: Future[Map[DelegateType, Status]] = storageWorker.storeAsset(assetToken, bytes, label)
    (assetToken, f)
  }

  def getStatus(token: AssetToken): Future[Map[DelegateType, Status]] =
    storageWorker.getStatus(token)

  def genToken(data: Array[Byte]): AssetToken = new AssetToken(data.hashCode.toString)

  def updateAndBroadcastMapping(mappingStr: String): Future[String ] =
    (for {
      mapping <- Future{MappingModel.fromJsonStr(mappingStr)}
      _ <- mapping.store(appConfig.mappingpath)
      _ <- mapping.broadcastUpdate(appConfig.rmq.qSender, appConfig.eventHeader)
    } yield mapping).recover { case _ => this.mapping }.map (MappingModel.toJson)

  def set(newMapping:MappingModel):Unit = this.mapping = newMapping

  def loadMapping(): Future[String] = {
      val oldMapping = this.mapping
      val loadAndSetFuture =for {
        newMapping:MappingModel <- MappingModel.load(appConfig.mappingpath)
        _ = set(newMapping)
      }yield MappingModel.toJson(newMapping)

      loadAndSetFuture.recover[String]{case _ => MappingModel.toJson(oldMapping)}
  }
}









