Feature: Storing a resource for later retrieval
  As an API consumer
  I want to be able to store a file and be given a mapped URL
  So that I can retrieve the file at a later date, wherever it is stored

  Scenario: Storing a new file
    Given I have a file that doesn't already exist in the storage-service
    When I request that the file be stored
    Then the request is successfully accepted
    And the response body is a resource status message

  Scenario: Storing a previously uploaded file
    Given I have a file that already exists in the storage-service
    When I request that the file be stored
    Then the request is successful
    And the response body is a resource status message

  Scenario: Store with an invalid label (doesn't exist)
    Given I have a file that doesn't already exist in the storage-service
    When I request that the file be stored with a label that doesn't exist
    Then the request fails because it is invalid

  Scenario: Store with an invalid label (wrong format string)
    Given I have a file that doesn't already exist in the storage-service
    When I request that the file be stored with a label that is invalid
    Then the request fails because it is invalid
