/*
 * Zinc - The incremental compiler for Scala.
 * Copyright Lightbend, Inc. and Mark Harrah
 *
 * Licensed under Apache License 2.0
 * (http://www.apache.org/licenses/LICENSE-2.0).
 *
 * See the NOTICE file distributed with this work for
 * additional information regarding copyright ownership.
 */

package sbt.inc

import java.nio.file.{ Files, Path, StandardCopyOption }

import sbt.internal.inc._
import sbt.io.IO
import TestResource._

class MultiProjectIncrementalSpec extends BaseCompilerSpec {
  // uncomment this to see the debug log
  // override def logLevel = sbt.util.Level.Debug

  "incremental compiler" should "detect shadowing" in {
    IO.withTemporaryDirectory { tempDir =>
      // Second subproject
      val sub2Directory = tempDir.toPath / "sub2"
      Files.createDirectories(sub2Directory / "src")

      // Prepare the initial compilation
      val sub1Directory = tempDir.toPath / "sub1"
      Files.createDirectories(sub1Directory / "src")
      Files.createDirectories(sub1Directory / "lib")
      val dependerFile = sub1Directory / "src" / "Depender.scala"
      Files.copy(dependerFile0, dependerFile, StandardCopyOption.REPLACE_EXISTING)
      val depender2Content =
        """package test.pkg
          |
          |object Depender2 {
          |  val x = test.pkg.Ext2.x
          |}
          |""".stripMargin
      val depender2File = StringVirtualFile("src/Depender2.scala", depender2Content)
      val binarySampleFile = sub1Directory / "lib" / "sample-binary_2.12-0.1.jar"
      Files.copy(binarySampleFile0, binarySampleFile, StandardCopyOption.REPLACE_EXISTING)

      val p2 = VirtualSubproject
        .Builder()
        .baseDirectory(sub2Directory)
        .get
      val p1 = VirtualSubproject
        .Builder()
        .baseDirectory(sub1Directory)
        .dependsOn(p2)
        .externalDependencies(binarySampleFile)
        .get
      def assertExists(p: Path) = assert(Files.exists(p), s"$p does not exist")
      try {
        // This registers `test.pkg.Ext1` as the class name on the binary stamp
        p1.compile(p1.p2vf(dependerFile))
        assertExists(p1.setup.output.resolve("test/pkg/Depender$.class"))
        assertExists(p1.setup.earlyOutput)

        // This registers `test.pkg.Ext2` as the class name on the binary stamp,
        // which means `test.pkg.Ext1` is no longer in the stamp.
        p1.compile(p1.p2vf(dependerFile), depender2File)
        assertExists(p1.setup.output.resolve("test/pkg/Depender2$.class"))
        assertExists(p1.setup.earlyOutput)

        // Second subproject
        val ext1File = sub2Directory / "src" / "Ext1.scala"
        Files.copy(ext1File0, ext1File, StandardCopyOption.REPLACE_EXISTING)
        p2.compile(p2.p2vf(ext1File))

        // Actual test
        val knownSampleGoodFile = sub1Directory / "src" / "Good.scala"
        Files.copy(knownSampleGoodFile0, knownSampleGoodFile, StandardCopyOption.REPLACE_EXISTING)
        val depender3File = StringVirtualFile(
          "src/Depender3.java",
          """package test.pkg;
            |
            |public class Depender3 {
            |  public static Ext1 x = new Ext1();
            |}""".stripMargin
        )
        val result3 = p1.compile(
          p1.p2vf(knownSampleGoodFile),
          p1.p2vf(dependerFile),
          depender2File,
          depender3File
        )
        val a3 = result3.analysis.asInstanceOf[Analysis]
        p1.compileAllJava(
          p1.p2vf(knownSampleGoodFile),
          p1.p2vf(dependerFile),
          depender2File,
          depender3File
        )

        // Depender.scala should be invalidated since it depends on test.pkg.Ext1 from the JAR file,
        // but the class is now shadowed by sub2/target.
        assert(lastClasses(a3) contains "test.pkg.Depender")
      } finally {
        p1.close()
        p2.close()
      }
    }
  }

  "it" should "not compile Java for no-op" in {
    IO.withTemporaryDirectory { tempDir =>
      // Second subproject
      val sub2Directory = tempDir.toPath / "sub2"
      Files.createDirectories(sub2Directory / "src")

      // Prepare the initial compilation
      val sub1Directory = tempDir.toPath / "sub1"
      Files.createDirectories(sub1Directory / "src")

      val p1 = VirtualSubproject
        .Builder()
        .baseDirectory(sub1Directory)
        .get
      val p2 = VirtualSubproject
        .Builder()
        .baseDirectory(sub2Directory)
        .dependsOn(p1)
        .get
      try {
        // First subproject
        val file1 = StringVirtualFile("src/Foo.scala", """package test.pkg
            |
            |class Foo {
            |}""".stripMargin)
        p1.compile(file1)

        val file2 = StringVirtualFile("src/Bar.scala", """package test.pkg
            |
            |class Bar {
            |  def foo = new Foo
            |}""".stripMargin)
        val file3 = StringVirtualFile(
          "src/Use.java",
          """package test.pkg;
            |
            |public class Use {
            |  public static Foo x = new Bar().foo();
            |}""".stripMargin
        )
        p2.compile(file2, file3)
        val result0 = p2.compileAllJava(file2, file3)
        val a0 = result0.analysis.asInstanceOf[Analysis]

        // This should be no-op
        val result1 = p2.compile(file2, file3)

        val result2 =
          if (result1.hasModified) p2.compileAllJava(file2, file3)
          else result1
        val a2 = result2.analysis.asInstanceOf[Analysis]
        assert(
          a0.compilations.allCompilations
            .map(_.getStartTime)
            .toList == a2.compilations.allCompilations.map(_.getStartTime).toList
        )

        // This should trigger compilation
        val file3b = StringVirtualFile(
          "src/Use.java",
          """package test.pkg;
            |
            |public class Use {
            |  public static Foo y = new Bar().foo();
            |}""".stripMargin
        )
        val result3 = p2.compile(file2, file3b)
        val result4 =
          if (result3.hasModified) p2.compileAllJava(file2, file3b)
          else result3
        val a4 = result4.analysis.asInstanceOf[Analysis]
        assert(
          a0.compilations.allCompilations
            .map(_.getStartTime)
            .toList != a4.compilations.allCompilations.map(_.getStartTime).toList
        )
      } finally {
        p1.close()
        p2.close()
      }
    }
  }

  def lastClasses(a: Analysis) = {
    val allCompilations = a.compilations.allCompilations
    val recompiledClasses: Seq[Set[String]] = allCompilations map { c =>
      val recompiledClasses = a.apis.internal.collect {
        case (className, api) if api.compilationTimestamp() == c.getStartTime => className
      }
      recompiledClasses.toSet
    }
    recompiledClasses.last
  }
}

/* Make a jar with the following:

package test.pkg

object Ext1 {
  val x = 1
}

object Ext2 {
  val x = 2
}

object Ext3 {
  val x = 3
}

object Ext4 {
  val x = 4
}

object Ext5 {
  val x = 5
}

object Ext6 {
  val x = 6
}

object Ext7 {
  val x = 7
}

object Ext8 {
  val x = 8
}

object Ext9 {
  val x = 9
}

object Ext10 {
  val x = 10
}

 */
