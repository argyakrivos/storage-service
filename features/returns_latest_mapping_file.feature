@QuarterMaster
Feature: Get the mapping file needed to retrieve a resource from a mapped URL
  As a blinkbox Books service
  I want to be able to retrieve data which allows me to convert a mapped URL into a real one
  So that I can download the file it refers to, no matter what the current location is.

  @smoke
  Scenario: Retrieve a mapping file
    When I request the mapping file
    Then the response is a mapping update
