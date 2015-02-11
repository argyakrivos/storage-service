package com.blinkbox.books.storageservice

import com.blinkbox.books.storageservice.util.{CommonMapping, StoreMappingUtils, LocalStorageStore}
import org.mockito.Mockito._
import org.scalatest.mock.MockitoSugar
import org.scalatest.{FlatSpec, Matchers}

class MappingUtilsTests extends FlatSpec with Matchers with MockitoSugar {

  "Mapping Utilities" should "load the mapping file correctly" in new MockedMappingUtils {
    val expected = Array(CommonMapping("testfile", "^bbbmap:testfile:(?<path>.+)$", Map("filesystem" -> "file://%{path}")))
    val testMappingLocation = getClass.getResource("/test-mapping.json").getPath
    mappings(testMappingLocation) should be (expected)
  }

  it should "instantiate Daos correctly from the mappings file" in new MockedMappingUtils {
    val testMappingLocation = getClass.getResource("/test-mapping.json").getPath
    val testMappings = mappings(testMappingLocation)
    val mapping = testMappings.head
    when(localStorageConfig.storagePath).thenReturn("/")
    when(appConfig.localStorageConfig).thenReturn(localStorageConfig)
    val localStorageDao = new LocalStorageStore(appConfig.localStorageConfig, mapping.label, mapping.extractor, mapping.providers)
    val expected = Array(localStorageDao)
    val result = mappingsToDao(testMappings)
    result.length should be (1)
    result.head should equal (localStorageDao)
  }

  class MockedMappingUtils extends StoreMappingUtils {
    override val appConfig = mock[AppConfig]
    val localStorageConfig = mock[LocalStorageConfig]
  }
}
