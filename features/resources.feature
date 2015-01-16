@QuarterMaster
Feature: Reading and Storing a resource for later retrieval
  As an API consumer
  I want to be able to store a file and be given a mapped URL
  So that I can retrieve the file at a later date, wherever it is stored

  Scenario: Storing a file (all storage providers are available)
    Given a local storage provider is configured
    When I request that a file be stored via tagging on local storage
    Then the response is a storage pending document

  Scenario: Storing a file (all storage providers are unavailable)
    Given a local storage provider is configured
    And the local storage providers is inaccessible
    When I request that a file be stored via tagging on local storage
    Then the request fails because no suitable storage providers were available
    And the response is a storage status failed document

  Scenario: Requesting a file that exists in storage by token
  Scenario: Requesting a file that does not exist in storage by token
  Scenario: Invalid tokens on read/store
  Scenario: Store with an invalid label (doesn't exist)
  Scenario: Store with an invalid label (wrong format string)
