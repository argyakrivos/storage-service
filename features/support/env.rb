require 'cucumber/blinkbox'
require 'cucumber/rest'
require 'httpclient'
require 'httpclient/capture'

TEST_CONFIG['server'] = ENV['SERVER'] || 'local'
TEST_CONFIG['debug'] = !!(ENV["DEBUG"] =~ /^on|true$/i)

puts "TEST_CONFIG: #{TEST_CONFIG}" if TEST_CONFIG['debug']
