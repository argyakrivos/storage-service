# Change log
## 0.2.4 ([#17](https://git.mobcastdev.com/Quartermaster/storage-service/pull/17) 2015-01-20 11:04:40)

Functional tests

Patch

## 0.2.3 ([#16](https://git.mobcastdev.com/Quartermaster/storage-service/pull/16) 2015-01-16 13:52:39)

MV-336 Add in formats for tokens and labels. Add in 200 and 400 cases

Patch

## 0.2.2 ([#15](https://git.mobcastdev.com/Quartermaster/storage-service/pull/15) 2015-01-15 11:00:36)

Added the Loggers to get graylog working

### Patch

- Added graylog support by mixing in the `Loggers` trait
- Fix for [MV-303](http://jira.blinkbox.local/jira/browse/MV-303)

## 0.2.1 ([#14](https://git.mobcastdev.com/Quartermaster/storage-service/pull/14) 2015-01-14 17:22:27)

Swagger file cleanup

Patch that gets the Swagger YAML to at least render in the Swagger UI.

## 0.2.0 ([#13](https://git.mobcastdev.com/Quartermaster/storage-service/pull/13) 2015-01-12 11:04:19)

Copy default mappings file

### New feature

- Copies the default (testing) mapping file to the defined location if none is present.

## 0.1.6 ([#12](https://git.mobcastdev.com/Quartermaster/storage-service/pull/12) 2015-01-09 16:46:31)

bugfix : use the variable after its initialisation

bugfix use the storage service after it has been initialised

## 0.1.5 ([#11](https://git.mobcastdev.com/Quartermaster/storage-service/pull/11) 2014-12-05 17:04:17)

fix: Mapping as json, fix to make it work

### Bug fixes

- fix to add the storage location in the header when requested


## 0.1.4 ([#10](https://git.mobcastdev.com/Quartermaster/storage-service/pull/10) 2014-11-27 11:16:01)

Patch: Major Refactoring , also ensuring json schema compliant replies , Mapping as json

Patch: fix refactoring of storage service, 
included code fix to ensure that service always returns json as per schema
include test to ensure code fix works as specified

## 0.1.3 ([#9](https://git.mobcastdev.com/Quartermaster/storage-service/pull/9) 2014-11-17 18:22:29)

CP-1710: bug fix for storage configs, using standard configs, 

bug fix: problems with merging , small pull request to effect change https://git.mobcastdev.com/Quartermaster/storage-service/pull/8

## 0.1.2 ([#6](https://git.mobcastdev.com/Quartermaster/storage-service/pull/6) 2014-11-14 16:04:22)

Patch: CP-1710: renamed Delegate references to Provider to keep language...

Patch: CP-1710: renamed Delegate references to Provider to keep language consistent

## 0.1.1 ([#5](https://git.mobcastdev.com/Quartermaster/storage-service/pull/5) 2014-11-14 11:20:12)

CP-1710: reconfigured boot service

Patch: CP-1710: added rabbitmq configs, renamed some vars

## 0.1.0 ([#4](https://git.mobcastdev.com/Quartermaster/storage-service/pull/4) 2014-11-06 16:11:34)

this is the rebase first commit please review

### New Features

- Stores mapping files
- Responds to `GET /mapping` requests with the current mapping file
- Responds to `POST /resources` to upload files (storing them with the filesystem provider at the moment)
- Responds to `GET /resources/{token}` and returns info about the specified asset

