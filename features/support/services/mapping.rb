module KnowsAboutMappingApi
  def get_mapping_file
    http_get :storage_service, "mappings", "Accept" => "application/vnd.blinkbox.books.ingestion.storageservice.v2+json"
    @response_data = parse_last_api_response
  end
end

World(KnowsAboutMappingApi)
