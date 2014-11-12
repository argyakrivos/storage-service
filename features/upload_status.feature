@QuarterMaster
Feature: Return information about mapped files' storage status
  As an blinkbox Books service
  I want to be able to query whether a mapped file has been stored effectively
  So that I can have confidence in the storage redundancy to my level of need

  Scenario: A file is present in all storage locations
    Given there are 2 storage providers configured
    And a file has been uploaded to both
    When I request the storage status for that file's mapped URI
    Then the response is a storage status document
    And there are two items in "Providers: Ready"

  Scenario: A file is present in some storage locations
    Given there are 2 storage providers configured
    And a file has finshed uploading to 1 and is being uploaded to the other
    When I request the storage status for that file's mapped URI
    Then the response is a storage status document
    And there is one item in "Providers: Ready"
    And there is one item in "Providers: Pending"

  Scenario: A file is not present in any storage locations
    Given there are 2 storage providers configured
    And a file has not been uploaded to either
    When I request the storage status for that file's mapped URI
    Then the response is a storage status document
    And there are no items in "Providers: Ready"
