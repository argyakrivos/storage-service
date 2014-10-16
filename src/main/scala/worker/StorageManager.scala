package worker

import akka.actor.Props
import com.blinkbox.books.spray.{v2, Directives => CommonDirectives}
import com.blinkbox.books.storageservice.{RestRoutes, AppConfig}
import common.{Progress, AssetToken, Status}
import spray.http.StatusCodes
import spray.http.StatusCodes._
import spray.routing._

import scala.collection.concurrent.TrieMap
import scala.concurrent.Future

/**
 * Created by greg on 16/10/14.
 */
class QuarterMasterStorageRoutes(qmss:QuarterMasterStorageService) extends HttpServiceActor with RestRoutes with CommonDirectives with v2.JsonSupport {
  val appConfig = qmss.appConfig
  val storageWorker = appConfig.hsc.arf.actorOf(Props(new QuarterMasterStorageWorkerRoutes(new QuarterMasterStorageWorkerService(appConfig))),"storageWorker")
  override def getAll: Route = {
    get {
      pathEndOrSingleSlash {
        uncacheable(InternalServerError, None)
      }
    }
  }



  def receive = {
    case sr@StorageRequest(data,label) =>
      val token:AssetToken = qmss.genToken(data)
      storageWorker ! new StorageWorkerRequest(token, sr)
      complete(token)
    case token@AssetToken(_) =>
      complete(StatusCodes.OK, qmss.getStatus(token).get)

  }
}

case class QuarterMasterStorageService(appConfig:AppConfig)  {
  def getStatus(token: AssetToken):Option[Status] = repo.get(token) map (Status.toStatus(_))

  val repo:TrieMap[AssetToken,Progress] = new TrieMap[AssetToken, Progress]



  def genToken(data:Array[Byte]):AssetToken = new AssetToken(data.hashCode.toString)




}

trait StorageService {

  def storeAsset(token:AssetToken, data:Array[Byte]):Future[AssetToken]
  def getProgress(token:AssetToken):Progress

}

case class StorageRequest(data:Array[Byte], label : Int )