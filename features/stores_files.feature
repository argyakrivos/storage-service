@QuarterMaster
Feature: Store a Resource for later retrieval
  As an API consumer
  I want to be able to store a file and be given a mapped URL
  So that I can retrieve the file at a later date, wherever it is stored.

  Scenario: Storing a file (all storage providers are available)
    Given there are 2 storage providers configured
    When I request that a file be stored
    Then the response is a storage status document
    And there are two items in "Providers: Ready"

  Scenario: Storing a file (some storage providers are unavailable)
    Given there are 2 storage providers configured
    And one storage provider is down
    When I request that a file be stored
    Then the response is a storage status document
    And there is one item in "Providers: Ready"
    And there is one item in "Providers: Pending"

  Scenario: Storing a file (all storage providers are unavailable)
    Given there are 2 storage providers configured
    And both storage providers are down
    When I request that a file be stored
    Then the request fails because no suitable storage providers were available
    And the response is a storage status document
    And there are two items in "Providers: Unavailable"
