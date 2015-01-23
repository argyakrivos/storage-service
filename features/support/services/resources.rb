module KnowsAboutResources
  def get_testfile_token_for(path)
    "bbbmap:testfile:#{path}"
  end

  def lookup_information_by_token(token)
    http_get :storage_service, "resources/#{token.url_encode}", "Accept" => "application/vnd.blinkbox.books.v2+json"
    @response_data = parse_last_api_response
  end

  def upload_resource(request_contents = {})
    http_post :storage_service, "resources", request_contents, "Accept" => "application/vnd.blinkbox.books.v2+json"
    @response_data = parse_last_api_response
  end
end

World(KnowsAboutResources)
