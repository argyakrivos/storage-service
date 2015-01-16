@QuarterMaster
Feature: Mapping API
  As a blinkbox Books service
  I want to be able to retrieve data which allows me to convert a mapped token into a file URI
  So that I can download the file it refers to, no matter what the current location is

  @smoke
  Scenario: Retrieve a mapping file
    Given the QuarterMaster is up
    When I request the mapping file
    Then QuarterMaster returns a mapping file
    And the response Json has the following attributes:
      | attribute | type   | description                                                                                        |
      | extractor | String | some group capturing regex                                                                         |
      | json      | Json   | Array of json objects , the object has members {servicename, Array of TagIds, substitution url}");"     |
