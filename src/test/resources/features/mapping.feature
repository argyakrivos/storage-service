@catalogue @books @QuarterMaster

Feature: MappingFileDistribution
As an API consumer
I want to be retrieve an up to date mapping file

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
