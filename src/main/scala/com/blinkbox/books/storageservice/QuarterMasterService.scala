package com.blinkbox.books.storageservice

import com.blinkbox.books.storageservice.util.{CommonMapping, StoreMappingUtils, Token}
import com.typesafe.scalalogging.StrictLogging
import scala.concurrent.{ExecutionContext, Future}

case class TokenNotFound(code: String, providers: Array[String])
case class ProviderStatus(token: Token, label: String, providers: Map[String, Any])
case class UnknownDaoForLabel(message: String) extends Exception(message)

class QuarterMasterService(config: AppConfig)(implicit executor: ExecutionContext) extends StoreMappingUtils with StrictLogging {

  override val appConfig: AppConfig = config

  val mappings: Array[CommonMapping] = mappings(appConfig.mapping.path)
  val daos = mappingsToDao(mappings)

  def storeAsset(label: String, bytes: Array[Byte]): Future[Option[ProviderStatus]] = {
    daos.find(_.label == label) match {
      case Some(dao) => dao.write(bytes, s"$label/${QuarterMasterService.createFileName(bytes)}").map(getTokenStatus)
      case _ => Future.failed(new UnknownDaoForLabel(s"Could not find DAO for label $label"))
    }
  }

  def getTokenStatus(token: Token): Option[ProviderStatus] =
    daos.map(_.uploadStatusUpdate(token)).find(_.isDefined).flatten
}

object QuarterMasterService {
  val md = java.security.MessageDigest.getInstance("MD5")
  def createFileName(bytes: Array[Byte]): String = {
    md.digest(bytes).map(0xFF & _).map { "%02x".format(_) }.foldLeft(""){_ + _}
  }
}