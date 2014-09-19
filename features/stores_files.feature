@QuarterMaster
Feature: Store a Resource for later retrieval
  As an API consumer
  I want to be able to store a file and be given a mapped URL
  So that I can retrieve the file at a later date, wherever it is stored.

  Scenario: Storing a file (all storage providers are available)
    Given there are storage providers 1,2,3 configured
    When I request that a file be stored via tagging on Providers 1,2,3
    Then the response is a storage pending document
    And the the inprogress status is stored in the repository

  Scenario: Storing a file (some storage providers are unavailable)
    Given there are storage providers 1,2,3 configured
    And  storage provider 1 is down
    When I request that a file be stored via tagging on Providers 1,2,3
    Then the response is a storage pending document
    And the the inprogress status is stored in the repository

  Scenario: Storing a file (all storage providers are unavailable)
    Given there are storage providers 1,2,3 configured
    And  storage providers 1,2,3 are down
    When I request that a file be stored via tagging on Providers 1,2,3
    Then the request fails because no suitable storage providers were available
    And the the inprogress status is stored in the repository
    And the response is a storage status failed document

  Scenario: Storing a file (all storage providers are unavailable)
    Given there are storage providers 1,2,3 configured
    And  a previous request to store a resource failed on storage provider 1
    And  storage providers 1,2,3 are up
    When I request the url for the resource on storage provider 1
    Then the quartermaster recreates the request to store on storage provider 1
    And the the inprogress status is stored in the repository
    And the response is a storage status document
