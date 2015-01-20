package com.blinkbox.books.storageservice

import com.blinkbox.books.storageservice.util.{UploadStatus, CommonMapping, DaoMappingUtils, Token}
import com.typesafe.scalalogging.StrictLogging
import sun.misc.BASE64Encoder

import scala.concurrent.Future

case class TokenNotFound(code: String, providers: Array[String])
case class ProviderStatus(token: Token, label: String, providers: Map[String, Any])
case class UnknownDaoForLabel(message: String) extends Exception(message)

class QuarterMasterService(config: AppConfig) extends DaoMappingUtils with StrictLogging {

  override val appConfig: AppConfig = config
  val md = java.security.MessageDigest.getInstance("SHA-1")
  val e64Encoder = new BASE64Encoder
  val mappings: Array[CommonMapping] = mappings(appConfig.mapping.path)
  val daos = mappingsToDao(mappings)

  def createFileName(bytes: Array[Byte]): String = {
    e64Encoder.encode(md.digest(bytes))
  }

  def storeAsset(label: String, bytes: Array[Byte]): Future[Token] = {
    daos.find(d => d.label == label) match {
      case Some(dao) => dao.write(bytes, s"$label/${createFileName(bytes)}")
      case _ => Future.failed(new UnknownDaoForLabel(s"Could not find DAO for label $label"))
    }
  }

  def getTokenStatus(token: Token): Option[ProviderStatus] =
    daos.map(_.uploadStatusUpdate(token)).find(_.isDefined).flatten

}
