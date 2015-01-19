Feature: Lookup a resource by token
  As an API consumer
  I want to be able to lookup a file using a mapped token
  So that I can see information on where to download this file from

  Scenario: Requesting a file that exists in storage by token
    Given a resource has been stored by the storage-service
    And I have a storage token representing this resource
    When I request information for the storage token
    Then the request is successful
    And the response body is a resource status message

  Scenario: Requesting a file that does not exist in storage by token
    Given I have a storage token representing a file that has not yet been uploaded
    When I request information for the storage token
    Then the request fails because the resource was not found

  Scenario Outline: Requesting the mapping for an invalid token
    Given I have the storage token "<token>"
    When I request information for the storage token
    Then the request fails because it was invalid

  Examples: Invalid tokens
    | token                                                           | description                             |
    | invalid_namespace:testfile:/mnt/m2/storage/testing/exists.epub  | Invalid namespace. Expected bbbmap      |
    | bbbmap:חשוד:/mnt/m2/storage/testing/exists.epub                 | Invalid label                           |
    | just_a_string                                                   | No namespace and no storage label.      |
