name := "storage-service"

scalaVersion := "2.11.4"
 
version := scala.util.Try(scala.io.Source.fromFile("VERSION").mkString.trim).getOrElse("0.0.0")

//testOptions in Test += Tests.Argument("-oDF")

scalacOptions := Seq("-unchecked", "-deprecation", "-feature", "-encoding", "utf8", "-target:jvm-1.7")

unmanagedResourceDirectories in Test += baseDirectory.value / "schemas"

resolvers += "Maven Central" at "https://repo1.maven.org/maven2/"
 
libraryDependencies ++= {
  val akkaV = "2.3.6"
  val sprayV = "1.3.1"
  val json4sV = "3.2.10"
  Seq(
    "io.spray"                  %% "spray-testkit"      % sprayV    % Test,
    "org.json4s"                %% "json4s-jackson"     % json4sV,
    "com.blinkbox.books"        %% "common-messaging"   %  "1.1.4",
    "com.typesafe.akka"         %% "akka-slf4j"         % akkaV,
    "com.typesafe.akka"         %% "akka-testkit"       % akkaV     % Test,
    "com.blinkbox.books"        %% "common-scala-test"  % "0.3.0"   % Test,
    "com.blinkbox.books"        %% "common-spray"       % "0.18.0",
    "com.blinkbox.books"        %% "common-spray-auth"  % "0.7.4",
    "com.blinkbox.books.hermes" %% "rabbitmq-ha"        % "7.1.0",
    "org.scalacheck"            %% "scalacheck"         % "1.11.5"  % Test,
    "com.github.fge"            % "json-schema-validator" % "2.2.6" % Test
  )
}

rpmPrepSettings
