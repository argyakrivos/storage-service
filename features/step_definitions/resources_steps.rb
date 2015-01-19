Given(/I have the storage token "([^"]+)"/) do |token|
  @storage_token = token
end

Given(/a resource has been stored by the storage-service/) do
  @file = data_for_a(:file, which: "exists in storage")
end

Given(/I have a storage token representing this resource/) do
  @storage_token = get_testfile_token_for(@file)
end

Given(/I have a storage token representing a file that has not yet been uploaded/) do
  @storage_token = "bbbmap:testfile:/mnt/m2/storage/thishasnotbeenuploaded.thing"
end

When(/I request information for the storage token/) do
  lookup_information_by_token(@storage_token)
end

Then(/the response body is a resource status message/) do
  expected_keys = Set.new(["token", "label", "providers"])
  expect { Set.new(@response_data.keys) }.to eq(expected_keys)

  ["token", "label"].each do |attribute|
    expect(@response_data[attribute]).to be_a(String)
  end

  expect(@response_data["providers"]).to be_a(Hash)
end
