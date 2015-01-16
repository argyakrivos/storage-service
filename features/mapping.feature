Feature: Mapping API
  As a blinkbox Books service
  I want to be able to retrieve data which allows me to convert a mapped token into a file URI
  So that I can download the file it refers to, no matter what the current location is

  @smoke
  Scenario: Retrieve a mappings file
    When I request the mappings file
    Then the request is successful
    And the response is an array of mappings with the following attributes:
      | attribute | type    |
      | label     | String  |
      | extractor | String  |
      | providers | Hash    |
