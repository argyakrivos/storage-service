name := "storage-service"

scalaVersion := "2.11.4"
 
version := scala.util.Try(scala.io.Source.fromFile("VERSION").mkString.trim).getOrElse("0.0.0")

testOptions in Test += Tests.Argument("-oDF")

scalacOptions := Seq("-unchecked", "-deprecation", "-feature", "-encoding", "utf8", "-target:jvm-1.7")

unmanagedResourceDirectories in Test += baseDirectory.value / "schemas"

libraryDependencies ++= {
  val akkaV = "2.3.8"
  val sprayV = "1.3.2"
  val json4sV = "3.2.11"
  Seq(
    "com.blinkbox.books"        %% "common-messaging"      % "2.1.1",
    "com.typesafe.akka"         %% "akka-slf4j"            % akkaV,
    "com.typesafe.akka"         %% "akka-testkit"          % akkaV      % Test,
    "com.blinkbox.books"        %% "common-config"         % "2.3.1",
    "com.blinkbox.books"        %% "common-scala-test"     % "0.3.0"    % Test,
    "com.blinkbox.books"        %% "common-spray"          % "0.24.0",
    "com.blinkbox.books"        %% "common-spray-auth"     % "0.7.6",
    "com.github.fge"            %  "json-schema-validator" % "2.2.6" % Test,
    "com.google.jimfs"          %  "jimfs"                 % "1.0"     % Test,
    "org.scalacheck"            %% "scalacheck"            % "1.12.0"   % Test,
    "io.spray"                  %% "spray-testkit"         % sprayV     % Test,
    "org.json4s"                %% "json4s-jackson"        % json4sV
  )
}

rpmPrepSettings
