

name := "quartermaster"
 
scalaVersion := "2.11.2"
 
version := scala.util.Try(scala.io.Source.fromFile("VERSION").mkString.trim).getOrElse("0.0.0")

resolvers ++= Seq(
  "sonatype-snapshots" at "http://oss.sonatype.org/content/repositories/snapshots",
  "sonatype-releases"  at "http://oss.sonatype.org/content/repositories/releases")

resolvers ++= Seq(
  "snapshots" at "http://scala-tools.org/repo-snapshots",
  "releases"  at "http://scala-tools.org/repo-releases")



scalacOptions := Seq("-unchecked", "-deprecation", "-feature", "-encoding", "utf8", "-target:jvm-1.7")
 
libraryDependencies ++= {
  val akkaV = "2.3.6"
  val sprayV = "1.3.1"
  val json4sV = "3.2.10"
  val scalazVersion = "7.0.6"
  Seq(
    "io.spray"                  %% "spray-testkit"      % sprayV    % Test,
    "org.scalaz"                %% "scalaz-core"        % scalazVersion,
    "org.scalaz"                %% "scalaz-effect"      % scalazVersion,
    "org.scalaz"                %% "scalaz-typelevel"   % scalazVersion,
    "org.scalaz"                %% "scalaz-scalacheck-binding" % scalazVersion %  Test,
    "org.json4s"                %% "json4s-jackson"     % json4sV,
    "com.blinkbox.books"        %% "common-messaging"   %  "1.1.4",
    "com.typesafe.akka"         %% "akka-slf4j"         % akkaV,
    "com.typesafe.akka"         %% "akka-testkit"       % akkaV     % Test,
    "com.blinkbox.books"        %% "common-scala-test"  % "0.3.0"   % Test,
    "com.blinkbox.books"        %% "common-spray"       % "0.17.0",
    "com.blinkbox.books"        %% "common-spray-auth"  % "0.7.0",
    "com.blinkbox.books.hermes" %% "rabbitmq-ha"        % "7.1.0",
    "org.scalacheck"            %% "scalacheck"         % "1.11.5"  % Test
  )
}

rpmPrepSettings
