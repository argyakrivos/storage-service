When(/^I request the mappings file$/) do
  get_mapping_file
end

Then(/^the response is an array of mappings with the following attributes:$/) do |attribute_table|
  @response_data.each do |mapping|
    attribute_table.rows.each do |attribute|
      expect(mapping.keys).to include(attribute[0])
      expect(mapping[attribute[0]]).to be_a(attribute[1].constantize)
    end
  end
end
