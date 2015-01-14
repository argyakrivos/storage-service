# storage-service

This service allows other services to store blob data and receive URLs from tokens; for where it can then be retrieved.

Please see the [feature list](./features) for information on what it does.

## Running the app/tests

Ensure you're initiated and updated the git submodules. Specifically; the `schemas`

```
git submodule init
git submodule update
```

To run the unit/integration tests, execute `sbt test`

To run the app, execute `sbt run`

For the functional tests, you'll need the Ruby dependancies installed first by running `bundle install`. 

Once this is done, you can use the rake tasks thusly; `rake test`
