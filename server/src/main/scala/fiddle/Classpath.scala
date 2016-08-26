package fiddle

import java.io._
import java.nio.file.{Files, Paths}

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model._
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.FileIO
import fiddle.cache._
import org.apache.maven.artifact.versioning.ComparableVersion
import org.scalajs.core.tools.io.{RelativeVirtualFile, _}
import org.slf4j.LoggerFactory

import scala.collection.mutable
import scala.concurrent.Future
import scala.concurrent.duration._
import scala.reflect.io.Streamable
import scala.tools.nsc.io.AbstractFile
import scalaz.concurrent.Task

case class ExtLib(group: String, artifact: String, version: String, compileTimeOnly: Boolean) {
  override def toString: String = s"$group ${if (compileTimeOnly) "%%" else "%%%"} $artifact % $version"
}

class JarEntryIRFile(outerPath: String, val relativePath: String)
  extends MemVirtualSerializedScalaJSIRFile(s"$outerPath:$relativePath")
    with RelativeVirtualFile

class VirtualFlatJarFile(flatJar: FlatJar, ffs: FlatFileSystem) extends VirtualJarFile {
  override def content: Array[Byte] = null
  override def path: String = flatJar.name
  override def exists: Boolean = true

  override val sjsirFiles: Seq[VirtualScalaJSIRFile with RelativeVirtualFile] = {
    flatJar.files.filter(_.path.endsWith("sjsir")).map { file =>
      val content = ffs.load(flatJar, file.path)
      new JarEntryIRFile(flatJar.name, file.path).withContent(content).withVersion(Some(path))
    }
  }
}

object ExtLib {
  val repoSJSRE = """([^ %]+) *%%% *([^ %]+) *% *([^ %]+)""".r
  val repoRE = """([^ %]+) *%% *([^ %]+) *% *([^ %]+)""".r

  def apply(libDef: String): ExtLib = libDef match {
    case repoSJSRE(group, artifact, version) =>
      ExtLib(group, artifact, version, false)
    case repoRE(group, artifact, version) =>
      ExtLib(group, artifact, version, true)
    case _ =>
      throw new IllegalArgumentException(s"Library definition '$libDef' is not correct")
  }
}

/**
  * Loads the jars that make up the classpath of the scala-js-fiddle
  * compiler and re-shapes it into the correct structure to satisfy
  * scala-compile and scalajs-tools
  */
class Classpath {
  implicit val system = ActorSystem()
  implicit val materializer = ActorMaterializer()
  implicit val ec = system.dispatcher

  val log = LoggerFactory.getLogger(getClass)
  val timeout = 60.seconds

  val baseLibs = Seq(
    s"/scala-library-${Config.scalaVersion}.jar",
    s"/scala-reflect-${Config.scalaVersion}.jar",
    s"/scalajs-library_${Config.scalaMainVersion}-${Config.scalaJSVersion}.jar",
    s"/page_sjs${Config.scalaJSMainVersion}_${Config.scalaMainVersion}-${Config.version}.jar"
  )

  val repoBase = "https://repo1.maven.org/maven2"
  val sjsVersion = s"_sjs${Config.scalaJSMainVersion}_${Config.scalaMainVersion}"

  def buildRepoUri(ref: ExtLib) = {
    ref match {
      case ExtLib(group, artifact, version, false) =>
        s"$repoBase/${group.replace('.', '/')}/$artifact$sjsVersion/$version/$artifact$sjsVersion-$version.jar"
      case ExtLib(group, artifact, version, true) =>
        s"$repoBase/${group.replace('.', '/')}/${artifact}_${Config.scalaMainVersion}/$version/${artifact}_${Config.scalaMainVersion}-$version.jar"
    }
  }

  def loadExtLib(libDef: ExtLib) = {
    val uri = buildRepoUri(libDef)
    val name = libDef.group.replace('.', '_') + "_" + uri.split('/').last
    // check if it has been loaded already
    val f = new File(Config.libCache, name)
    if (f.exists()) {
      log.debug(s"Loading $name from ${Config.libCache}")
      Future {(name, Files.readAllBytes(f.toPath))}
    } else {
      log.debug(s"Loading $name from $uri")
      f.getParentFile.mkdirs()
      Http().singleRequest(HttpRequest(uri = uri)).flatMap { response =>
        val source = response.entity.dataBytes
        // save to cache
        val sink = FileIO.toPath(f.toPath)
        source.runWith(sink).map { ioResponse =>
          log.debug(s"Storing $name with ${ioResponse.count} bytes to cache")
          (name, Files.readAllBytes(f.toPath))
        }
      } recover {
        case e: Exception =>
          log.debug(s"Error loading $uri: $e")
          throw e
      }
    }
  }

  def commonLibraries = {
    log.debug("Loading common libraries...")
    val jarFiles = baseLibs.par.map { name =>
      val stream = getClass.getResourceAsStream(name)
      log.debug(s"Loading resource $name")
      if (stream == null) {
        throw new Exception(s"Classpath loading failed, jar $name not found")
      }
      name -> Streamable.bytes(stream)
    }.seq

    val bootFiles = for {
      prop <- Seq(/*"java.class.path", */ "sun.boot.class.path")
      path <- System.getProperty(prop).split(System.getProperty("path.separator"))
      vfile = scala.reflect.io.File(path)
      if vfile.exists && !vfile.isDirectory
    } yield {
      path.split("/").last -> vfile.toByteArray()
    }
    log.debug("Common libraries loaded...")
    jarFiles ++ bootFiles
  }

  val commonJars = {
    log.debug("Loading common libraries...")
    val jarFiles = baseLibs.par.map { name =>
      val stream = getClass.getResourceAsStream(name)
      log.debug(s"Loading resource $name")
      if (stream == null) {
        throw new Exception(s"Classpath loading failed, jar $name not found")
      }
      name -> stream
    }.seq

    val bootFiles = for {
      prop <- Seq(/*"java.class.path", */ "sun.boot.class.path")
      path <- System.getProperty(prop).split(System.getProperty("path.separator"))
      vfile = scala.reflect.io.File(path)
      if vfile.exists && !vfile.isDirectory
    } yield {
      val name = "system/" + path.split(File.separatorChar).last
      log.debug(s"Loading resource $name")
      name -> vfile.inputStream()
    }
    log.debug("Common libraries loaded...")
    jarFiles ++ bootFiles
  }

  import coursier._
  def loadCoursier(libs: Seq[ExtLib]) = {
    import scalaz._

    log.debug(s"Loading: $libs")

    val repositories = Seq(
      Cache.ivy2Local,
      MavenRepository("https://repo1.maven.org/maven2")
    )
    val exclusions = Set(
      ("org.scala-lang", "scala-reflect"),
      ("org.scala-lang", "scala-library"),
      ("org.scala-js", s"scalajs-library_${Config.scalaMainVersion}"),
      ("org.scala-js", s"scalajs-test-interface_${Config.scalaMainVersion}")
    )
    val results = Task.gatherUnordered(libs.map { lib =>
      val dep = lib match {
        case ExtLib(group, artifact, version, false) =>
          Dependency(Module(group, artifact + sjsVersion), lib.version, exclusions = exclusions)
        case ExtLib(group, artifact, version, true) =>
          Dependency(Module(group, s"${artifact}_${Config.scalaMainVersion}"), lib.version, exclusions = exclusions)
      }
      val start = Resolution(Set(dep))
      val fetch = Fetch.from(repositories, Cache.fetch())
      start.process.run(fetch).map(res => (lib, res))
    }).run
    results.foreach { case (lib, r) =>
      val root = r.rootDependencies.head
      if (r.errors.nonEmpty) {
        log.error(r.errors.toString)
      }
      log.debug(s"Deps for ${root.moduleVersion}:")
      r.minDependencies.foreach { dep =>
        log.debug(s"   ${dep.moduleVersion}")
      }
    }
    val depArts = results.flatMap(_._2.dependencyArtifacts).groupBy(_._2.url).map(_._2.head).toSeq
    // log.debug(s"Artifacts: ${depArts.map(_._2.url).mkString("\n")}")

    val jars = Task.gatherUnordered(depArts.map(da => Cache.file(da._2).map(f => (da._1, f.toPath)).run)).run.map {
      case \/-((dep, path)) =>
        (dep, path.toString, new FileInputStream(path.toFile))
      case -\/(error) =>
        throw new Exception(s"Unable to load a library: ${error.describe}")
    }

    val ffs = FlatFileSystem.build(Paths.get(Config.libCache), jars.map(j => (j._2, j._3)) ++ commonJars)
    val absffs = new AbstractFlatFileSystem(ffs)

    val jarFlatFiles = jars.map(jar => (jar._1, absffs.roots(jar._2)))
    val commonJarFlatFiles = commonJars.map(jar => (jar._1, absffs.roots(jar._1))).toMap
    // load all JARs
/*
    val artifacts = Task.gatherUnordered(depArts.map(da => Cache.file(da._2).map(f => (da._1, Files.readAllBytes(f.toPath))).run)).run.flatMap {
      case \/-(dep) =>
        log.debug(s"Cached ${dep._1.module.organization}-${dep._1.module.name}=${dep._1.version}")
        Some(dep)
      case -\/(error) =>
        throw new Exception(s"Unable to load a library: ${error.describe}")
    }
*/
    // create a result map
/*
    val extLibMap = results.map { case (lib, resolution) =>
      (lib, resolution.minDependencies.map(dep => (dep, artifacts.find(_._1.moduleVersion == dep.moduleVersion).get._2)))
    }.toMap
*/
    val commonLibs = commonJars.map { case (jar, _) => jar -> commonJarFlatFiles(jar)}
    val extLibMap = results.map { case (lib, resolution) =>
      (lib, resolution.minDependencies.map(dep => (dep, jarFlatFiles.find(_._1.moduleVersion == dep.moduleVersion).get._2)))
    }.toMap

    (commonLibs, extLibMap, ffs, absffs)
  }

  def resolveDeps(deps: Seq[Dependency]): Seq[Dependency] = {
    deps.groupBy(_.module).map { case (_, versions) =>
      // sort by version, select latest
      versions.sortBy(lib => new ComparableVersion(lib.version)).last
    }.toSeq
  }

  /**
    * External libraries loaded from repository
    */
  log.debug("Loading external libraries")
  val (commonLibs, extLibraries, ffs, absffs) = loadCoursier(Config.extLibs)

  val classCache = new FlatClassLoader(ffs)

  val flatDeps = extLibraries.flatMap(_._2).groupBy(_._1).mapValues(_.head._2)

  /**
    * The loaded files shaped for Scala-Js-Tools to use
    */
    def lib4linker(file: AbstractFlatJar) = {
    val jarFile = new VirtualFlatJarFile(file.flatJar, ffs)
    IRFileCache.IRContainer.Jar(jarFile)
  }

  /**
    * In memory cache of all the jars used in the compiler. This takes up some
    * memory but is better than reaching all over the filesystem every time we
    * want to do something.
    */
  val commonLibraries4compiler = commonLibs.map { case (name, data) => data.root }.seq
  val dependency4compiler = flatDeps.map { case (dep, data) => dep -> data.root }.seq

  /**
    * In memory cache of all the jars used in the linker.
    */
  val commonLibraries4linker = commonLibs.map { case (name, file) => lib4linker(file) }
  val dependency4linker = flatDeps.map { case (dep, file) => dep -> lib4linker(file) }

  def deps(extLibs: Set[ExtLib]) = {
    val resolved = resolveDeps(extLibs.flatMap(lib => extLibraries(lib).map(_._1)).toList)
    log.debug(s"Resolved libraries: ${resolved.map(_.moduleVersion)}")
    resolved
  }

  def compilerLibraries(extLibs: Set[ExtLib]): Seq[AbstractFile] = {
    commonLibraries4compiler ++ deps(extLibs).map(dep => dependency4compiler(dep))
  }

  val linkerCaches = mutable.Map.empty[Set[ExtLib], Seq[IRFileCache.VirtualRelativeIRFile]]

  def linkerLibraries(extLibs: Set[ExtLib]) = {
    this.synchronized {
      linkerCaches.getOrElseUpdate(extLibs, {
        val loadedJars = commonLibraries4linker ++ deps(extLibs).map(dep => dependency4linker(dep))
        val cache = (new IRFileCache).newCache
        val res = cache.cached(loadedJars)
        log.debug(s"Cached $extLibs")
        res
      })
    }
  }
}
