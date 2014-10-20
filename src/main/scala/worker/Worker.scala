package worker

import java.io.FileOutputStream
import java.nio.ByteBuffer

import com.blinkbox.books.spray.{Directives => CommonDirectives}
import com.blinkbox.books.storageservice._
import common._
import spray.http.DateTime

import scala.collection.concurrent.TrieMap
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

/**
 * Created by greg on 16/10/14.
 */



trait StorageService {

  def storeAsset(token:AssetToken, data:Array[Byte], label:Int):Future[Set[AssetToken]]
  def getProgress(token:AssetToken):Future[Set[Progress]]

}

case class DelegatedAssetToken(delegateName:String, assetToken:AssetToken)

trait StorageDelegate {
  val repo:TrieMap[DelegatedAssetToken,Progress]

  val name :String

  def getStatus(token: DelegatedAssetToken):Option[Status] = repo.get(token) map (Status.toStatus(_))

  def genToken(data:Array[Byte]):AssetToken = new AssetToken(data.hashCode.toString)

  def storeProgress = repo.put _

  def getProgress(assetToken:DelegatedAssetToken):Progress = repo.get(assetToken).get

  def removeProgress(assetToken:DelegatedAssetToken) ={
    repo.remove(assetToken)
  }

  def updateProgress(assetToken:DelegatedAssetToken, size:Long, started:DateTime, bytesWritten:Long) ={
    repo.putIfAbsent(assetToken, new Progress(new AssetData(started, size), bytesWritten)).map(
      (oldProgress:Progress) => repo.put(assetToken,Progress(oldProgress.assetData, bytesWritten))
    )

  }
  def write(assetToken:AssetToken, data: Array[Byte]):AssetToken
}


case class QuarterMasterStorageWorker(swConfig:StorageWorkerConfig ) extends StorageService {


  val delegates = swConfig.delegates
  val repo = swConfig.repo

  val delegateNames: Set[String] = swConfig.delegates.values.foldLeft[Set[String]](Set.empty)((a: Set[String], b: Set[StorageDelegate]) => (a.union(b.map((_.name)))))

  def getDelegates(label: Int): Set[StorageDelegate] = delegates.get(label).getOrElse(Set.empty)


  override def storeAsset(assetToken: AssetToken, data: Array[Byte], label: Int): Future[Set[AssetToken]] =
    Future.traverse(getDelegates(label))((sd: StorageDelegate) => Future {
      sd.write(assetToken, data)
    })


  def getProgress(assetToken: AssetToken): Future[Set[Progress]] = {
  val futOfSetOfOption = Future.traverse(delegateNames)((delegateName: String) =>
    Future {
      repo.get(new DelegatedAssetToken(delegateName, assetToken))
    }
  )

  futOfSetOfOption.map((_.flatten))
}

}


case class LocalStorageDelegate(repo:TrieMap[DelegatedAssetToken,Progress],  path: String) extends StorageDelegate{
  val name :String = "localStorage"
  override def write(assetToken:AssetToken, data: Array[Byte]):AssetToken= {
    // import spray.httpx.SprayJsonSupport._
    val fos: FileOutputStream = new FileOutputStream(path);
    val channel = fos.getChannel
    val numBytes: Long = data.length
    val started: DateTime = DateTime.now
    val delegatedToken = new DelegatedAssetToken(name, assetToken)
    updateProgress(delegatedToken,numBytes, started, 0)
    try {



      val  buf:ByteBuffer = ByteBuffer.allocate(48);
      buf.clear();
      buf.put(data);

      buf.flip();

      while(buf.hasRemaining()) {
        channel.write(buf)
        updateProgress(delegatedToken,numBytes, started, buf.position())
      }

      removeProgress(delegatedToken)
      assetToken

    } finally {
      fos.close()

    }
  }


}


