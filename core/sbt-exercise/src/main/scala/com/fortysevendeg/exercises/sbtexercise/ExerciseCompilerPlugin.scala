/*
 * scala-exercises-sbt-exercise
 * Copyright (C) 2015-2016 47 Degrees, LLC. <http://www.47deg.com>
 */

package com.fortysevendeg.exercises
package sbtexercise

import scala.language.implicitConversions
import scala.language.reflectiveCalls

import scala.collection.mutable.ArrayBuffer
import java.io.OutputStream
import java.io.PrintStream

import sbt.{ `package` ⇒ _, _ }
import sbt.Keys._
import sbt.classpath.ClasspathUtilities
import xsbt.api.Discovery

import cats.data.Ior
import cats.data.Xor
import cats.std.all._
import cats.syntax.flatMap._
import cats.syntax.traverse._

/** The exercise compiler SBT auto plugin */
object ExerciseCompilerPlugin extends AutoPlugin {

  // this plugin requires the JvmPlugin and must be
  // explicitly enabled by projects
  override def requires = plugins.JvmPlugin
  override def trigger = noTrigger

  val CompileMain = config("compile-main")
  val CompileExercisesSource = config("compile-exercises-source") extend CompileMain
  val CompileExercises = config("compile")

  val generateExercises = TaskKey[List[(String, String)]]("pregen-exercises")

  object autoImport {
    def CompileMain = ExerciseCompilerPlugin.CompileMain
    def CompileExercises = ExerciseCompilerPlugin.CompileExercises
    def CompileExercisesSource = ExerciseCompilerPlugin.CompileExercisesSource
  }

  // format: OFF
  override def projectSettings =
    inConfig(CompileMain)(
      Defaults.compileSettings ++
      Defaults.compileInputsSettings ++
      Defaults.configTasks
    ) ++
    inConfig(CompileExercisesSource)(
      Defaults.compileSettings ++
      Defaults.compileInputsSettings ++
      Seq(
        unmanagedSourceDirectories := Seq(baseDirectory.value / "src" / "main" / "scala")
      )
    ) ++
    inConfig(CompileExercises)(
      // this configuration compiles source code we generate in CompileExercisesSource
      Defaults.compileSettings ++
      Defaults.compileInputsSettings ++
      Defaults.configTasks ++ Seq(

      fork := false,

      products := {
        products.value ++
        (products in CompileExercisesSource).value ++
        (products in CompileMain).value
      },

      // disable any user defined source files for this scope as
      // we only want to compile the generated files
      sourceGenerators <+= generateExerciseSourcesTask,
      resourceGenerators <+= generateExerciseDescriptorTask,
      generateExercises <<= generateExercisesTask
    )) ++
    inConfig(Compile)(
      // All your base are belong to us!! (take over standard compile)
      classpathConfiguration := CompileExercises
    ) ++
    Seq(
      defaultConfiguration := Some(CompileMain),

      // library dependencies have to be declared at the root level
      ivyConfigurations   := overrideConfigs(CompileMain, CompileExercisesSource, CompileExercises)(ivyConfigurations.value),
      libraryDependencies += "com.47deg" %% "definitions" % Meta.version % CompileExercisesSource.name,
      libraryDependencies += "com.47deg" %% "runtime" % Meta.version % CompileExercises.name
    )
  // format: ON

  // for most of the work below, a captured error is an error message and/or a
  // throwable value
  private type Err = Ior[String, Throwable]
  private implicit def errFromString(message: String): Ior[String, Nothing] = Ior.left(message)
  private implicit def errFromThrowable(throwable: Throwable): Ior[Nothing, Throwable] = Ior.right(throwable)

  private def catching[A](f: ⇒ A)(msg: ⇒ String) =
    Xor.catchNonFatal(f).leftMap(e ⇒ Ior.both(msg, e))

  /** Given an Analysis output from a compile run, this will
    * identify all modules implementing `exercise.Library`.
    */
  private def discoverLibraries(analysis: inc.Analysis): Seq[String] =
    Discovery(Set("exercise.Library"), Set.empty)(Tests.allDefs(analysis))
      .collect({
        case (definition, discovered) if !discovered.isEmpty ⇒ definition.name
      }).sorted

  private def discoverSections(analysis: inc.Analysis): Seq[String] =
    Discovery(Set("exercise.Section"), Set.empty)(Tests.allDefs(analysis))
      .collect({
        case (definition, discovered) if !discovered.isEmpty ⇒ definition.name
      }).sorted

  // reflection is used to invoke a java-style interface to the exercise compiler
  private val COMPILER_CLASS = "com.fortysevendeg.exercises.compiler.CompilerJava"
  private type COMPILER = {
    def compile(library: AnyRef, sources: Array[String], targetPackage: String): Array[String]
  }

  // worker task that invokes the exercise compiler
  def generateExercisesTask = Def.task {
    val log = streams.value.log
    log.info("compiling scala exercises")

    lazy val analysisIn = (compile in CompileExercisesSource).value

    lazy val libraryNames = discoverLibraries(analysisIn)
    lazy val sectionNames = discoverSections(analysisIn)

    val libraryClasspath = Attributed.data((fullClasspath in CompileExercisesSource).value)

    val loader = ClasspathUtilities.toLoader(
      (Meta.compilerClasspath ++ libraryClasspath).distinct,
      null,
      ClasspathUtilities.createClasspathResources(
        appPaths = Meta.compilerClasspath,
        bootPaths = scalaInstance.value.jars
      )
    )

    def loadLibraryModule(name: String) = for {
      loadedClass ← catching(loader.loadClass(name + "$"))(s"$name not found")
      loadedModule ← catching(loadedClass.getField("MODULE$").get(null))(s"$name must be defined as an object")
    } yield loadedModule

    def invokeCompiler(compiler: COMPILER, library: AnyRef): Xor[Err, (String, String)] =
      Xor.catchNonFatal {
        val sourceCodes = (libraryNames ++ sectionNames).toSet
          .flatMap(analysisIn.relations.definesClass)
          .map(IO.read(_))
        captureStdStreams(
          fOut = log.info(_: String),
          fErr = log.error(_: String)
        ) {
            compiler.compile(
              library = library,
              sources = sourceCodes.toArray,
              targetPackage = "defaultLib"
            ).toList
          }
      } leftMap (e ⇒ e: Err) >>= {
        case mName :: moduleSource :: Nil ⇒ Xor.right(mName → moduleSource)
        case _                            ⇒ Xor.left("Unexpected return value from exercise compiler": Err)
      }

    val result = for {
      compilerClass ← catching(loader.loadClass(COMPILER_CLASS))("Unable to find exercise compiler class")
      compiler ← catching(compilerClass.newInstance.asInstanceOf[COMPILER])("Unable to create instance of exercise compiler")
      libraries ← libraryNames.map(loadLibraryModule).toList.sequenceU
      result ← libraries.map(invokeCompiler(compiler, _)).sequenceU
    } yield result

    result.fold({
      case Ior.Left(message) ⇒ throw new Exception(message)
      case Ior.Right(error)  ⇒ throw error
      case Ior.Both(message, error) ⇒
        log.error(message)
        throw error
    }, identity)
  }

  // task responsible for outputting the source files
  def generateExerciseSourcesTask = Def.task {
    val log = streams.value.log
    val generated = generateExercises.value
    val dir = (sourceManaged in CompileExercises).value
    generated.map {
      case (fName, code) ⇒
        val file = dir / (fName.replace(".", "/") + ".scala")
        IO.write(file, code)
        log.info(s"Generated library at $file")
        file
    }
  }

  // task responsible for outputting the exercise descriptor resource
  def generateExerciseDescriptorTask = Def.task {
    val generated = generateExercises.value
    val qualifiedLibraryInstancies = generated.map(_._1 + "$")
    val dir = (resourceManaged in CompileExercises).value
    val resourceFile = dir / "scala-exercises" / "library.47"
    IO.write(resourceFile, qualifiedLibraryInstancies.mkString("\n"))
    Seq(resourceFile)
  }

  private[this] def captureStdStreams[T](fOut: (String) ⇒ Unit, fErr: (String) ⇒ Unit)(thunk: ⇒ T): T = {
    val originalOut = System.out
    val originalErr = System.err
    System.setOut(new PrintStream(new LineByLineOutputStream(fOut), true))
    System.setErr(new PrintStream(new LineByLineOutputStream(fErr), true))
    val res: T = thunk
    System.setOut(originalOut)
    System.setErr(originalErr)
    res
  }

  private[this] class LineByLineOutputStream(f: (String) ⇒ Unit) extends OutputStream {
    val ls = System.getProperty("line.separator")
    val buf = new ArrayBuffer[Byte](200)
    override def write(b: Int) = if (b != 0) {
      buf += b.toByte
    }
    override def flush() {
      if (buf.nonEmpty) {
        val message = new String(buf.toArray)
        buf.clear()
        if (message != ls) f(message)
      }
    }
  }

}