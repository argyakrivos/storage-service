@QuarterMaster
Feature: Get the mapping file needed to retrieve a resource from a mapped URL
  As a blinkbox Books service
  I want to be able to retrieve data which allows me to convert a mapped URL into a real one
  So that I can download the file it refers to, no matter what the current location is.

  @smoke
  Scenario: Retrieve a mapping file (old)
    Given the QuarterMaster is up
    When I request the mapping file that is old
    Then QuarterMaster returns a mapping file with a last update of <date>
    And the response Json has the following attributes:
      | attribute | type   | description                                                                                        |
      | extractor | String | some group capturing regex                                                                         |
      | json      | Json   | Array of json objects , the object has members {servicename, Array of TagIds, substitution url}");"     |

  @smoke
  Scenario: Retrieve a mapping file (new)
    Given the QuarterMaster is up
    When I request the mapping file that is up to date
    Then QuarterMaster returns a status of code of upto date
