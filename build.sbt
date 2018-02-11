import sbt._
import Keys._

import scala.sys.process.Process
import java.io.{File => JFile}

lazy val libgdxVersion = settingKey[String]("LibGDX library version")
lazy val akkaVersion = settingKey[String]("master Akka ecosystem version")

lazy val nativeExtractions = SettingKey[Seq[(String, NameFilter, File)]](
  "native-extractions", "(jar name partial, sbt.NameFilter of files to extract, destination directory)"
)

lazy val assembly = TaskKey[Unit]("assembly", "Assembly using Proguard")
lazy val desktopJarName = SettingKey[String]("desktop-jar-name", "name of JAR file for desktop")


version := "0.1"
scalaVersion := "2.12.4"


lazy val coreSettings = plugins.JvmPlugin.projectSettings ++ Seq(
  libgdxVersion := "1.9.6",
    akkaVersion := "2.5.11",
  libraryDependencies ++= Seq(
    "com.badlogicgames.gdx" % "gdx" % libgdxVersion.value,
    "com.badlogicgames.gdx" % "gdx-box2d" % libgdxVersion.value,
    "net.mikolak" %% "travesty" % s"0.9_${akkaVersion.value}"
  )  ++
    Seq("akka-stream"
    ).map("com.typesafe.akka" %% _ % akkaVersion.value),
  javacOptions ++= Seq(
    "-Xlint",
    "-encoding", "UTF-8",
    "-source", "1.8",
    "-target", "1.8"
  ),
  scalacOptions ++= Seq(
    "-Xlint",
    "-Ywarn-dead-code",
    "-Ywarn-value-discard",
    "-Ywarn-numeric-widen",
    "-Ywarn-unused",
    "-Ywarn-unused-import",
    "-unchecked",
    "-deprecation",
    "-feature",
    "-encoding", "UTF-8",
    "-target:jvm-1.8"
  ),
  cancelable := true,
  exportJars := true,
  resolvers += "indvd00m-github-repo" at "https://raw.githubusercontent.com/indvd00m/maven-repo/master/repository"
)

lazy val extractNatives = TaskKey[Unit]("extract-natives", "Extracts native files") 

lazy val natives = Seq(
  ivyConfigurations += config("natives"),
  nativeExtractions := Seq.empty,
  extractNatives := {
    val jars = update.value.select(configurationFilter("natives"))
    nativeExtractions.value foreach {
      case (jarName, fileFilter, outputPath) =>
        jars find (_.getName.contains(jarName)) map { jar =>
          IO.unzip(jar, outputPath, fileFilter)
        }
    }
  },
  (compile in Compile) dependsOn extractNatives
)


lazy val core = (project in file("core"))
.settings(coreSettings)

lazy val desktop = (project in file("desktop"))
    .settings(coreSettings ++ Seq(
      libraryDependencies ++= Seq(
        "net.sf.proguard" % "proguard-base" % "4.11" % "provided",
        "com.badlogicgames.gdx" % "gdx-backend-lwjgl" % libgdxVersion.value,
        "com.badlogicgames.gdx" % "gdx-box2d-platform" % libgdxVersion.value classifier "natives-desktop",
        "com.badlogicgames.gdx" % "gdx-platform" % libgdxVersion.value classifier "natives-desktop"
      ),
      fork in Compile := true,
      unmanagedResourceDirectories in Compile += file("android/assets"),
      desktopJarName := "bullet_hell",
      assembly := {
        val updates = (update in Compile).value
        val ioStreams = (streams in Compile).value

        val provided = Set(updates.select(configurationFilter("provided")): _*)
        val compile = Set(updates.select(configurationFilter("compile")): _*)
        val runtime = Set(updates.select(configurationFilter("runtime")): _*)
        val optional = Set(updates.select(configurationFilter("optional")): _*)
        val onlyProvidedNames = provided -- compile -- runtime -- optional
        val (onlyProvided, withoutProvided) =
          (dependencyClasspath in Compile).value.partition(cpe => onlyProvidedNames contains cpe.data)
        val exclusions = Seq("!META-INF/MANIFEST.MF", "!library.properties").mkString(",")
        val inJars = withoutProvided.map("\"" + _.data.absolutePath + "\"(" + exclusions + ")").mkString(JFile.pathSeparator)
        val libraryJars = onlyProvided.map("\"" + _.data.absolutePath + "\"").mkString(JFile.pathSeparator)
        val outfile = "\"" + (target.value / "%s-%s.jar".format(desktopJarName.value, version.value)).absolutePath + "\""
        val classfiles = "\"" + (classDirectory in Compile).value.absolutePath + "\""
        val manifest = "\"" + file("desktop/manifest").absolutePath + "\""
        val proguardOptions = scala.io.Source.fromFile(file("core/proguard-project.txt")).getLines.toList ++
          scala.io.Source.fromFile(file("desktop/proguard-project.txt")).getLines.toList
        val proguard = (javaOptions in Compile).value ++ Seq("-cp",
          Path.makeString((managedClasspath in Compile).value.files),
          "proguard.ProGuard") ++ proguardOptions ++ Seq("-injars",
          classfiles,
          "-injars",
          inJars,
          "-injars",
          manifest,
          "-libraryjars",
          libraryJars,
          "-outjars",
          outfile)
        ioStreams.log.info("preparing proguarded assembly")
        ioStreams.log.debug("Proguard command:")
        ioStreams.log.debug("java " + proguard.mkString(" "))
        val exitCode = Process("java", proguard) ! ioStreams.log
        if (exitCode != 0) {
          sys.error("Proguard failed with exit code [%s]" format exitCode)
        } else {
          ioStreams.log.info("Output file: " + outfile)
        }
      }
    ))
    .dependsOn(core)

lazy val game = (project in file("."))
    .settings(coreSettings)
    .aggregate(core, desktop)