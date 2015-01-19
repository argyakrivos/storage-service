require 'cucumber/blinkbox'
require 'cucumber/rest'
require 'httpclient'
require 'httpclient/capture'
require 'securerandom'
require 'set'

TEST_CONFIG['server'] = ENV['SERVER'] || 'local'
TEST_CONFIG['debug'] = !!(ENV["DEBUG"] =~ /^on|true$/i)

puts "TEST_CONFIG: #{TEST_CONFIG}" if TEST_CONFIG['debug']

class Object
  def url_encode
    URI.encode_www_form_component("#{self}")
  end
end
