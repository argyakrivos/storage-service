Given(/I have the storage token "([^"]+)"/) do |token|
  @storage_token = token
end

Given(/a resource has been stored by the storage-service/) do
  @file = data_for_a(:file, which: "exists in storage")
end

Given(/I have a storage token representing this resource/) do
  @storage_token = get_testfile_token_for(@file['path'])
end

Given(/^I have a resource that already exists in the storage-service$/) do
  @file = {}
  @file['contents'] = SecureRandom.uuid

  @label = data_for_a(:label, which: "exists")

  @upload_request = { "label" => @label, "data" => @file['contents'] }
  upload_resource(@upload_request)
  Cucumber::Rest::Status.ensure_status_class(:success)
end

Given(/I have a storage token representing a file that has not yet been uploaded/) do
  @storage_token = "bbbmap:testfile:/mnt/m2/storage/thishasnotbeenuploaded.thing"
end

Given(/^I have a resource that doesn't already exist in the storage-service$/) do
  @file = {}
  @file['contents'] = SecureRandom.uuid
end

Given(/^my request will be missing the (\w+) parameter$/) do |parameter|
  @missing_parameters ||= []
  @missing_parameters.push(parameter)
end

When(/I request information for the storage token/) do
  lookup_information_by_token(@storage_token)
end

When(/^I request that the resource be stored$/) do
  @label = data_for_a(:label, which: "exists")

  @upload_request = { "label" => @label, "data" => @file['contents'] }
  @missing_parameters.each do |param|
    @upload_request.delete(param)
  end if !@missing_parameters.nil?

  upload_resource(@upload_request)
end

When(/^I request that the resource be stored with a label that (.*)$/) do |stipulation|
  @label = data_for_a(:label, which: stipulation)

  @upload_request = { "label" => @label, "data" => @file['contents'] }
  upload_resource(@upload_request)
end

Then(/the response body is a resource status message/) do
  expected_keys = Set.new(["token", "label", "providers"])
  expect { Set.new(@response_data.keys) }.to eq(expected_keys)

  ["token", "label"].each do |attribute|
    expect(@response_data[attribute]).to be_a(String)
  end

  expect(@response_data["providers"]).to be_a(Hash)
end
