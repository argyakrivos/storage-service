package worker

import java.io.{File, FileOutputStream}
import java.nio.ByteBuffer

import com.blinkbox.books.spray.{Directives => CommonDirectives}
import com.blinkbox.books.storageservice._
import common._
import spray.http.DateTime

import scala.collection.concurrent.TrieMap
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future


trait StorageService {
  def storeAsset(token: AssetToken, data: Array[Byte], label: Int): Future[Map[DelegateType, Status]]
  def getProgress(token: AssetToken): Future[Map[DelegateType, Progress]]
  def getStatus(assetToken: AssetToken): Future[Map[DelegateType, Status]]
  def cleanUp(assetToken: AssetToken, label: Int): Future[Set[(DelegateType, Status)]]
}

trait StorageDelegate {
  val repo: TrieMap[DelegateKey, Progress]
  val delegateType: DelegateType
  def getStatus(token: AssetToken): Option[Status] = repo.get(new DelegateKey(delegateType, token)) map (Status.toStatus(_))
  def genToken(data: Array[Byte]): AssetToken = new AssetToken(data.hashCode.toString)
  def storeProgress = repo.put _
  def getProgress(token: AssetToken): Progress = repo.get(new DelegateKey(delegateType, token)).get

  def removeProgress(token: AssetToken) = {
    repo.remove(new DelegateKey(delegateType, token))
  }

  def updateProgress(token: AssetToken, size: Long, started: DateTime, bytesWritten: Long) = {
    repo.putIfAbsent(new DelegateKey(delegateType, token), new Progress(new AssetData(started, size), bytesWritten))
  }

  def write(assetToken: AssetToken, data: Array[Byte]): Future[(DelegateType, Status)]
  def cleanUp(assetToken: AssetToken): Future[(DelegateType, Status)]
}

case class QuarterMasterStorageWorker(swConfig: StorageWorkerConfig) extends StorageService {
  val delegates = swConfig.delegates
  val delegateTypes = swConfig.delegateTypes
  val repo = AppConfig.repo

  private def getDelegates(label: Int): collection.immutable.Set[StorageDelegate] = {
    val maybeDelegates: Option[Set[StorageDelegate]] = delegates.get(label)
    val size = maybeDelegates.map((_.size)).getOrElse(0)
    maybeDelegates.getOrElse(collection.immutable.Set.empty)
  }



  override def storeAsset(assetToken: AssetToken, data: Array[Byte], label: Int): Future[Map[DelegateType, Status]] =
    Future.traverse[StorageDelegate, (DelegateType, Status), Set](getDelegates(label))((sd: StorageDelegate) => {
      sd.write(assetToken, data)
    }).recoverWith(
    {
      case _ => cleanUp(assetToken, label)
    }
    ).map((_.toMap))

  override def getStatus(assetToken: AssetToken): Future[Map[DelegateType, Status]] = for {
    maybeTuples: Set[Option[(DelegateType, Status)]] <- Future.traverse(delegateTypes)((dt: DelegateType) =>
      Future {
        repo.get(new DelegateKey(dt, assetToken)).map((p: Progress) => (dt, Status.toStatus(p)))
      })
    strippedTuples = maybeTuples.flatten
  } yield (strippedTuples.toMap)


  override def getProgress(assetToken: AssetToken): Future[Map[DelegateType, Progress]] =
    for {
      maybeTuples: Set[Option[(DelegateType, Progress)]] <- Future.traverse(delegateTypes)((dt: DelegateType) => Future {
        repo.get(new DelegateKey(dt, assetToken)).map((dt, _))
      })
      strippedTuples = maybeTuples.flatten
    } yield (strippedTuples.toMap)

  override def cleanUp(assetToken: AssetToken, label: Int): Future[Set[(DelegateType, Status)]] = {
    val dirtyDelegates: Set[StorageDelegate] = getDelegates(label)
    for {
      result <- Future.traverse(dirtyDelegates)((sd: StorageDelegate) =>
        sd.cleanUp(assetToken)
      )
    } yield (result)
  }
}

case class LocalStorageDelegate(repo: TrieMap[DelegateKey, Progress], path: String, delegateType: DelegateType) extends StorageDelegate {

  def getPath(assetToken: AssetToken) = path + File.separator + assetToken.toFileString

  override def write(assetToken: AssetToken, data: Array[Byte]): Future[(DelegateType, Status)] = Future {
    val fos: FileOutputStream = new FileOutputStream(getPath(assetToken));
    val channel = fos.getChannel
    val numBytes: Long = data.length
    val started: DateTime = DateTime.now


    updateProgress(assetToken, numBytes, started, 0)
    try {
      val buf: ByteBuffer = ByteBuffer.allocate(48);
      buf.clear();
      buf.put(data);
      buf.flip();
      while (buf.hasRemaining()) {
        channel.write(buf)
        updateProgress(assetToken, numBytes, started, buf.position())
      }

      val status = Status.toStatus(this.getProgress(assetToken))
      removeProgress(assetToken)
      (delegateType, status)

    } finally {
      fos.close()
    }
  }


  override def cleanUp(assetToken: AssetToken): Future[(DelegateType, Status)] =
    Future {
      val file: File = new File(getPath(assetToken))
      
        file.delete
        removeProgress(assetToken)
        (delegateType, Status.neverStatus)

    }
}


