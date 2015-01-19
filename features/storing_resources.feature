Feature: Storing a resource for later retrieval
  As an API consumer
  I want to be able to store a resource and be given a mapped URL
  So that I can retrieve the resource at a later date, wherever it is stored

  Scenario: Storing a new resource
    Given I have a resource that doesn't already exist in the storage-service
    When I request that the resource be stored
    Then the request is successfully accepted
    And the response body is a resource status message

  Scenario: Storing a previously uploaded resource
    Given I have a resource that already exists in the storage-service
    When I request that the resource be stored
    Then the request is successful
    And the response body is a resource status message

  Scenario: Store with an invalid label (doesn't exist)
    Given I have a resource that doesn't already exist in the storage-service
    When I request that the resource be stored with a label that does not exist
    Then the request fails because it is invalid

  Scenario: Store with an invalid label (wrong format string)
    Given I have a resource that doesn't already exist in the storage-service
    When I request that the resource be stored with a label that is invalid
    Then the request fails because it is invalid

  Scenario Outline: Storage request with missing parameters
    Given I have a resource that doesn't already exist in the storage-service
    And my request will be missing the <parameter> parameter
    When I request that the resource be stored
    Then the request fails because it is invalid

  Examples: required parameters
    | parameter |
    | label     |
    | data      |
