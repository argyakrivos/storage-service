# Change log
## 0.1.0 ([#4](https://git.mobcastdev.com/Quartermaster/storage-service/pull/4) 2014-11-06 16:11:34)

this is the rebase first commit please review

### New Features

- Stores mapping files
- Responds to `GET /mapping` requests with the current mapping file
- Responds to `POST /resources` to upload files (storing them with the filesystem provider at the moment)
- Responds to `GET /resources/{token}` and returns info about the specified asset

