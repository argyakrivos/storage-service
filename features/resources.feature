Feature: Reading and Storing a resource for later retrieval
  As an API consumer
  I want to be able to store a file and be given a mapped URL
  So that I can retrieve the file at a later date, wherever it is stored

  Scenario: Storing a new file
    Given I have a file that doesn't already exist in the storage-service
    When I request that the file be stored
    Then the response is a 202
    And the response body is a resource status message

  Scenario: Storing a previously uploaded file
    Given I have a file that already exists in the storage-service
    When I request that the file be stored
    Then the response is a 200
    And the response body is a resource status message

  Scenario: Store with an invalid label (doesn't exist)
  Scenario: Store with an invalid label (wrong format string)
  
  Scenario: Aborting a file upload should not result in any knowledge of the resource
  Scenario: Retrying an aborted file upload should succeed

  Scenario: Requesting a file that exists in storage by token
    Given a resource has been stored by the storage-service
    And I have a storage token representing this resource
    When I request to lookup the storage token
    Then the response is successful
    And the response body is a resource status message

  Scenario: Requesting a file that does not exist in storage by token
    Given I have a storage token representing a file that has not yet been uploaded
    When I request to lookup the storage token
    Then the response fails because the resource was not found

  Scenario: Invalid tokens on read

