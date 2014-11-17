# Change log
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

