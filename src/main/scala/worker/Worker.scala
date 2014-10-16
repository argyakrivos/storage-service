package worker

import java.io.FileOutputStream
import java.nio.ByteBuffer

import akka.actor.Props
import com.blinkbox.books.spray.{v2, Directives => CommonDirectives}
import com.blinkbox.books.storageservice._
import common.{AssetData, Progress, AssetToken, Status}
import spray.http.DateTime
import spray.http.StatusCodes._
import spray.routing._
import scala.concurrent.ExecutionContext.Implicits.global
import scala.collection.concurrent.TrieMap
import scala.concurrent.Future

/**
 * Created by greg on 16/10/14.
 */
class QuarterMasterStorageWorkerRoutes(qmws:QuarterMasterStorageWorkerService) extends HttpServiceActor
with RestRoutes with CommonDirectives with v2.JsonSupport {


  val appConfig = qmws.appConfig
  val storageWorker = appConfig.hsc.arf.actorOf(Props(new QuarterMasterStorageWorkerRoutes(new QuarterMasterStorageWorkerService(appConfig))),"storageWorker")
  override def getAll: Route = {
    get {
      pathEndOrSingleSlash {
        uncacheable(InternalServerError, None)
      }
    }
  }



  def receive = {
    case StorageWorkerRequest(assetToken,StorageRequest(data, label)) =>
      complete(qmws.storeAsset(assetToken,data))
  }
}
case class StorageWorkerRequest(assetToken: AssetToken, storageRequest : StorageRequest)
case class QuarterMasterStorageWorkerService(appConfig: AppConfig) extends StorageService {


  def getStatus(token: AssetToken):Option[Status] = repo.get(token) map (Status.toStatus(_))

  val repo:TrieMap[AssetToken,Progress] = new TrieMap[AssetToken, Progress]

  def getPath(assetToken:AssetToken):String={
    appConfig.sc.localPath ++ assetToken.token
  }


  def genToken(data:Array[Byte]):AssetToken = new AssetToken(data.hashCode.toString)

  def storeProgress = repo.put _

  def getProgress(assetToken:AssetToken) = repo.get(assetToken).get

  def removeProgress(assetToken:AssetToken) ={
    repo.remove(assetToken)
  }

  def updateProgress(assetToken:AssetToken, size:Long, started:DateTime, bytesWritten:Long) ={

    repo.putIfAbsent(assetToken, new Progress(new AssetData(started, size), bytesWritten)).map(
      (oldProgress:Progress) => repo.put(assetToken,Progress(oldProgress.assetData, bytesWritten))
    )

  }


  //this should fan out to other actors
  override def storeAsset(assetToken:AssetToken, data: Array[Byte]):Future[AssetToken] = Future{
    // import spray.httpx.SprayJsonSupport._
    val fos: FileOutputStream = new FileOutputStream(getPath(assetToken));
    val channel = fos.getChannel
    val numBytes: Long = data.length
    val started: DateTime = DateTime.now
    updateProgress(assetToken,numBytes, started, 0)
    try {



      val  buf:ByteBuffer = ByteBuffer.allocate(48);
      buf.clear();
      buf.put(data);

      buf.flip();

      while(buf.hasRemaining()) {
        channel.write(buf);
        updateProgress(assetToken,numBytes, started, buf.position())
      }

      removeProgress(assetToken)
      assetToken

    } finally {
      fos.close();

    }
  }



}