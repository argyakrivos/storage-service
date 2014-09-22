@QuarterMaster
Feature: Delivers updates to mapping files to the Mapping exchange
  As a blinkbox Books service
  I want to be able to request that updates to the mapping file are delivered to me
  So that I can always have the latest mapping information in memory.

  @smoke
  Scenario: Posts mapping file updates
    Given I have created and bound a messaging queue to the Mapping exchange
    When the mapping details are changed
    Then a message is delivered to my queue
    And the content of the message is a mapping update
