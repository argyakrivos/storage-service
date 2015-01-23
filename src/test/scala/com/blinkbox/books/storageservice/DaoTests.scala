package com.blinkbox.books.storageservice

import java.nio.charset.StandardCharsets
import java.nio.file.{Paths, Files}

import com.blinkbox.books.storageservice.util.{LocalStorageStore, Token}
import com.google.common.jimfs.Jimfs
import org.mockito.Mockito._
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.mock.MockitoSugar
import org.scalatest.{Matchers, FlatSpec}

class DaoTests extends FlatSpec with Matchers with MockitoSugar with ScalaFutures {

  "A LocalStorageDao" should "write files to local storage" in new LocalStorageDaoTestFixtures {
    whenReady(localStorageDao.write(sampleBinaryFile, fs.getPath("sample_binary.jpg"))) { result =>
      val filePath = fs.getPath(result.getFileLocation)
      val resultBinaries = Files.readAllBytes(filePath)
      resultBinaries should equal(sampleBinaryFile)
    }
  }

  it should "provide the upload status report for an uploaded file" in new LocalStorageDaoTestFixtures {
    whenReady(localStorageDao.write(sampleBinaryFile, fs.getPath("sample_binary.jpg"))) { token =>
      val mockedToken = makeTokenFromPath(token.getFileLocation)
      val uploadStatus = localStorageDao.uploadStatusUpdate(mockedToken)
      val expectedStatus = ProviderStatus(mockedToken, label, Map("available" -> true))
      uploadStatus.isDefined should be(true)
      uploadStatus.get should equal(expectedStatus)
    }
  }

  it should "not provide a status report for a file that has not been uploaded" in new LocalStorageDaoTestFixtures {
    val mockedToken = makeTokenFromPath("/mnt/storage/imaginary.png")
    val uploadStatus = localStorageDao.uploadStatusUpdate(mockedToken)
    uploadStatus should be(None)
  }

  // For security reason, we don't want our service to give away information about files in
  // our File System outside of the location of our storage directory
  it should "not provide a status report for a file that is outside the root location" in new LocalStorageDaoTestFixtures {
    val otherDirectory = fs.getPath("/mnt/otherStorage")
    Files.createDirectories(otherDirectory)
    val filePath = otherDirectory.resolve("hello.txt")
    Files.write(filePath, "hello world".toArray.map(_.toByte))
    val mockedToken = makeTokenFromPath(s"file://${filePath.toString}")
    mockedToken.getFileLocation should be ("file:///mnt/otherStorage/hello.txt")
    val uploadStatus = localStorageDao.uploadStatusUpdate(mockedToken)
    uploadStatus should be(None)
  }

  class LocalStorageDaoTestFixtures {
    val fs = Jimfs.newFileSystem()
    fs.getPath("/")


    val appConfig = mock[AppConfig]
    val localStorageConfig = mock[LocalStorageConfig]
    val label = "testfile"
    val extractor = "^bbbmap:testfile:(?<path>.+)$"
    val providers = Map("filesytem" -> "file://${path}")
    when(localStorageConfig.storagePath).thenReturn("/mnt/storage")
    val localStorageDao = new LocalStorageStore(localStorageConfig, label, extractor, providers)
    val sampleBinaryFile = Files.readAllBytes(Paths.get(getClass.getResource("/sample_binary.jpg").getPath))

    // Stubbing token so that it uses JimFs paths
    def makeTokenFromPath(filePath: String): Token = {
      val token = mock[Token]
      when(token.token).thenReturn(s"bbbmap:testfile:$filePath")
      when(token.getFileLocation).thenReturn(filePath)
      when(token.getFilePath).thenReturn(fs.getPath(filePath))
      token
    }

  }
}
