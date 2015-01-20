require 'rake'

task :default => :test

desc "Runs all tests"
task :test do
  Rake::Task['features'].invoke
end

desc "Test all features"
begin
  require 'cucumber'
  require 'cucumber/rake/task'
  Cucumber::Rake::Task.new(:features)
rescue LoadError
  task :features do
    $stderr.puts "Please install cucumber: `bundle install`"
  end
end
