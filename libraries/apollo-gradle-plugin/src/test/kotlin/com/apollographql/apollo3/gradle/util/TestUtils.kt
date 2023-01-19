package com.apollographql.apollo3.gradle.util


import com.google.common.truth.Truth
import okio.blackholeSink
import okio.buffer
import org.gradle.testkit.runner.BuildResult
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.Assert
import java.io.File

object TestUtils {
  class Plugin(val artifact: String?, val id: String)

  val androidApplicationPlugin = Plugin(id = "com.android.application", artifact = "libs.plugins.android.application")
  val androidLibraryPlugin = Plugin(id = "com.android.library", artifact = "libs.plugins.android.library")
  val kotlinJvmPlugin = Plugin(id = "org.jetbrains.kotlin.jvm", artifact = "libs.plugins.kotlin.jvm")
  val kotlinAndroidPlugin = Plugin(id = "org.jetbrains.kotlin.android", artifact = "libs.plugins.kotlin.android")
  val apolloPlugin = Plugin(id = "com.apollographql.apollo3", artifact = "libs.plugins.apollo")

  fun withDirectory(testDir: String? = null, block: (File) -> Unit) {
    val dest = if (testDir == null) {
      File.createTempFile("testProject", "", File(System.getProperty("user.dir")).resolve("build"))
    } else {
      File(System.getProperty("user.dir")).resolve("build/$testDir")
    }
    dest.deleteRecursively()

    // See https://github.com/apollographql/apollo-android/issues/2184
    dest.mkdirs()
    File(dest, "gradle.properties").writeText("""
      |org.gradle.jvmargs=-Xmx4g 
      |
    """.trimMargin())

    try {
      block(dest)
    } finally {
      // Comment this line if you want to keep the directory around during development
      dest.deleteRecursively()
    }
  }

  fun withProject(
      usesKotlinDsl: Boolean,
      plugins: List<Plugin>,
      apolloConfiguration: String,
      isFlavored: Boolean = false,
      graphqlPath: String = "starwars",
      block: (File) -> Unit,
  ) = withDirectory {
    val source = fixturesDirectory()
    val dest = it

    File(source, graphqlPath).copyRecursively(target = File(dest, "src/main/graphql/com/example"))
    File(source, "gradle/settings.gradle").copyTo(target = File(dest, "settings.gradle"))

    val isAndroid = plugins.firstOrNull { it.id.startsWith("com.android") } != null

    val buildscript = buildString {
      appendLine("plugins {")
      plugins.forEach {
        appendLine("  alias(${it.artifact})")
      }
      appendLine("}")
      appendLine()

      append("""
        dependencies {
          add("implementation", libs.apollo.api)
        }
      """.trimIndent())

      appendLine()

      append(apolloConfiguration)

      appendLine()

      if (isAndroid) {
        append("""
            |android {
            |  compileSdkVersion(libs.versions.android.sdkversion.compile.get().toInteger())
            |
          """.trimMargin()
        )

        if (isFlavored) {
          append("""
            |  flavorDimensions "price"
            |  productFlavors {
            |    free {
            |      dimension 'price'
            |    }
            |
            |    paid {
            |      dimension 'price'
            |    }
            |  }
            |
            """.trimMargin())
        }

        append("""
            |}
          """.trimMargin())
      }
    }

    val filename = if (usesKotlinDsl) "build.gradle.kts" else "build.gradle"
    File(dest, filename).writeText(buildscript)

    if (isAndroid) {
      File(source, "manifest/AndroidManifest.xml").copyTo(File(dest, "src/main/AndroidManifest.xml"))
      File(dest, "local.properties").writeText("sdk.dir=${androidHome()}\n")
    }

    block(dest)
  }

  fun withGeneratedAccessorsProject(apolloConfiguration: String, block: (File) -> Unit) = withProject(
      usesKotlinDsl = true,
      plugins = listOf(kotlinJvmPlugin, apolloPlugin),
      apolloConfiguration = apolloConfiguration,
      block = block
  )

  fun withTestProject(name: String, testDir: String? = null, block: (File) -> Unit) = withDirectory(testDir) { dir ->
    File(System.getProperty("user.dir"), "testProjects/$name").copyRecursively(dir, overwrite = true)

    block(dir)
  }

  /**
   * creates a simple java non-android non-kotlin-gradle project
   */
  fun withSimpleProject(
      apolloConfiguration: String = """
    apollo {
      service("service") {
        packageNamesFromFilePaths()
      }
    }
  """.trimIndent(),
      block: (File) -> Unit,
  ) = withProject(
      usesKotlinDsl = false,
      plugins = listOf(kotlinJvmPlugin, apolloPlugin),
      apolloConfiguration = apolloConfiguration
  ) { dir ->
    File(fixturesDirectory(), "java").copyRecursively(File(dir, "src/main/java"))
    block(dir)
  }

  fun executeGradle(projectDir: File, vararg args: String): BuildResult {
    return executeGradleWithVersion(projectDir, null, *args)
  }

  fun executeGradleWithVersion(projectDir: File, gradleVersion: String?, vararg args: String): BuildResult {
//    val output = System.out.writer()
//    val error = System.err.writer()
    val output = blackholeSink().buffer().outputStream().writer()
    val error = blackholeSink().buffer().outputStream().writer()

    return GradleRunner.create()
        .forwardStdOutput(output)
        .forwardStdError(error)
        .withProjectDir(projectDir)
        .withDebug(true)
        .withArguments("--stacktrace", *args)
        .apply {
          if (gradleVersion != null) {
            withGradleVersion(gradleVersion)
          }
        }
        .build()
  }

  fun executeTask(task: String, projectDir: File, vararg args: String): BuildResult {
    return executeGradleWithVersion(projectDir, null, task, *args)
  }

  fun assertFileContains(projectDir: File, path: String, content: String) {
    val text = projectDir.generatedChild(path).readText()
    Truth.assertThat(text).contains(content)
  }

  fun fixturesDirectory() = File(System.getProperty("user.dir"), "src/test/files")

  fun executeTaskAndAssertSuccess(task: String, dir: File) {
    val result = executeTask(task, dir)
    Assert.assertEquals(TaskOutcome.SUCCESS, result.task(task)?.outcome)
  }
}

fun File.generatedChild(path: String) = File(this, "build/generated/source/apollo/$path")

fun File.replaceInText(oldValue: String, newValue: String) {
  val text = readText()
  writeText(text.replace(oldValue, newValue))
}
