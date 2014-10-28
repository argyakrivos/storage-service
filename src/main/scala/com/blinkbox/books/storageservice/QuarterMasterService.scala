package com.blinkbox.books.storageservice

import java.io.FileWriter

import com.blinkbox.books.spray.{Directives => CommonDirectives}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.concurrent.{Await, Future}
import scala.io.Source

trait MappingLoader {
  def load(path:String): String
  def write(path:String, json:String):Unit
}

case class FileMappingLoader() extends MappingLoader {

  override def load(path:String): String =
    Source.fromFile(path).mkString("")

  override def write(path:String, json: String): Unit = {
    val fw = new FileWriter(path)
    try {
      fw.write(json)
    }finally {
      fw.close()
    }
  }
}


case class QuarterMasterService(appConfig: AppConfig) {
  val storageWorker = new QuarterMasterStorageWorker(appConfig.swc)
  var mapping: Mapping = Await.result(MappingHelper.load(appConfig.mappingpath), 1000 millis)

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
      mapping <- Future{MappingHelper.fromJsonStr(mappingStr)}
      _ <- MappingHelper.store(appConfig.mappingpath, mapping)
      _ <- MappingHelper.broadcastUpdate(appConfig.rmq.qSender, appConfig.eventHeader, mapping)
    } yield mapping).recover { case _ => this.mapping }.map (MappingHelper.toJson)

  def set(newMapping:Mapping):Unit = this.mapping = newMapping

  def loadMapping(): Future[String] = {
      val oldMapping = this.mapping
      val loadAndSetFuture =for {
        newMapping:Mapping <- MappingHelper.load(appConfig.mappingpath)
        _ = set(newMapping)
      }yield MappingHelper.toJson(newMapping)

      loadAndSetFuture.recover[String]{case _ => MappingHelper.toJson(oldMapping)}
  }
}









