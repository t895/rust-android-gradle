package com.nishtahir

import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.dsl.CommonExtension
import com.android.build.api.dsl.LibraryExtension
import com.android.build.api.variant.AndroidComponentsExtension
import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import com.android.build.api.variant.LibraryAndroidComponentsExtension
import com.android.build.gradle.AppPlugin
import com.android.build.gradle.LibraryPlugin
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.file.DuplicatesStrategy
import java.io.File
import java.util.Properties

const val RUST_TASK_GROUP = "rust"

enum class ToolchainType {
    ANDROID_PREBUILT,
    DESKTOP,
}

// See https://forge.rust-lang.org/platform-support.html.
val toolchains = listOf(
    Toolchain(
        "linux-x86-64",
        ToolchainType.DESKTOP,
        "x86_64-unknown-linux-gnu",
        "<compilerTriple>",
        "<binutilsTriple>",
        "desktop/linux-x86-64"
    ),
    // This should eventually go away: the darwin-x86-64 target will supersede it.
    // https://github.com/mozilla/rust-android-gradle/issues/77
    Toolchain(
        "darwin",
        ToolchainType.DESKTOP,
        "x86_64-apple-darwin",
        "<compilerTriple>",
        "<binutilsTriple>",
        "desktop/darwin"
    ),
    Toolchain(
        "darwin-x86-64",
        ToolchainType.DESKTOP,
        "x86_64-apple-darwin",
        "<compilerTriple>",
        "<binutilsTriple>",
        "desktop/darwin-x86-64"
    ),
    Toolchain(
        "darwin-aarch64",
        ToolchainType.DESKTOP,
        "aarch64-apple-darwin",
        "<compilerTriple>",
        "<binutilsTriple>",
        "desktop/darwin-aarch64"
    ),
    Toolchain(
        "win32-x86-64-msvc",
        ToolchainType.DESKTOP,
        "x86_64-pc-windows-msvc",
        "<compilerTriple>",
        "<binutilsTriple>",
        "desktop/win32-x86-64"
    ),
    Toolchain(
        "win32-x86-64-gnu",
        ToolchainType.DESKTOP,
        "x86_64-pc-windows-gnu",
        "<compilerTriple>",
        "<binutilsTriple>",
        "desktop/win32-x86-64"
    ),
    Toolchain(
        "arm",
        ToolchainType.ANDROID_PREBUILT,
        "armv7-linux-androideabi",  // This is correct.  "Note: For 32-bit ARM, the compiler is prefixed with
        "armv7a-linux-androideabi", // armv7a-linux-androideabi, but the binutils tools are prefixed with
        "arm-linux-androideabi",    // arm-linux-androideabi. For other architectures, the prefixes are the same
        "android/armeabi-v7a"
    ),     // for all tools."  (Ref: https://developer.android.com/ndk/guides/other_build_systems#overview )
    Toolchain(
        "arm64",
        ToolchainType.ANDROID_PREBUILT,
        "aarch64-linux-android",
        "aarch64-linux-android",
        "aarch64-linux-android",
        "android/arm64-v8a"
    ),
    Toolchain(
        "x86",
        ToolchainType.ANDROID_PREBUILT,
        "i686-linux-android",
        "i686-linux-android",
        "i686-linux-android",
        "android/x86"
    ),
    Toolchain(
        "x86_64",
        ToolchainType.ANDROID_PREBUILT,
        "x86_64-linux-android",
        "x86_64-linux-android",
        "x86_64-linux-android",
        "android/x86_64"
    )
)

data class Ndk(val path: File, val version: String) {
    val versionMajor: Int
        get() = version.split(".").first().toInt()
}

data class Toolchain(
    val platform: String,
    val type: ToolchainType,
    val target: String,
    val compilerTriple: String,
    val binutilsTriple: String,
    val folder: String
) {
    fun cc(apiLevel: Int): File =
        if (System.getProperty("os.name").startsWith("Windows")) {
            if (type == ToolchainType.ANDROID_PREBUILT) {
                File("bin", "$compilerTriple$apiLevel-clang.cmd")
            } else {
                File("$platform-$apiLevel/bin", "$compilerTriple-clang.cmd")
            }
        } else {
            if (type == ToolchainType.ANDROID_PREBUILT) {
                File("bin", "$compilerTriple$apiLevel-clang")
            } else {
                File("$platform-$apiLevel/bin", "$compilerTriple-clang")
            }
        }

    fun cxx(apiLevel: Int): File =
        if (System.getProperty("os.name").startsWith("Windows")) {
            if (type == ToolchainType.ANDROID_PREBUILT) {
                File("bin", "$compilerTriple$apiLevel-clang++.cmd")
            } else {
                File("$platform-$apiLevel/bin", "$compilerTriple-clang++.cmd")
            }
        } else {
            if (type == ToolchainType.ANDROID_PREBUILT) {
                File("bin", "$compilerTriple$apiLevel-clang++")
            } else {
                File("$platform-$apiLevel/bin", "$compilerTriple-clang++")
            }
        }

    fun ar(apiLevel: Int, ndkVersionMajor: Int): File =
        if (ndkVersionMajor >= 23) {
            File("bin", "llvm-ar")
        } else if (type == ToolchainType.ANDROID_PREBUILT) {
            File("bin", "$binutilsTriple-ar")
        } else {
            File("$platform-$apiLevel/bin", "$binutilsTriple-ar")
        }
}

abstract class RustAndroidPlugin : Plugin<Project> {
    internal lateinit var cargoExtension: CargoExtension

    override fun apply(project: Project) {
        cargoExtension = project.extensions.create("cargo", CargoExtension::class.java)

        project.afterEvaluate {
            project.plugins.all {
                when (this) {
                    is AppPlugin -> configurePlugin<ApplicationExtension, ApplicationAndroidComponentsExtension>(
                        project
                    )

                    is LibraryPlugin -> configurePlugin<LibraryExtension, LibraryAndroidComponentsExtension>(
                        project
                    )
                }
            }
        }
    }

    private inline fun <reified Common : CommonExtension, reified Component : AndroidComponentsExtension<*, *, *>> configurePlugin(
        project: Project
    ) {
        cargoExtension.localProperties = Properties()

        val localPropertiesFile = File(project.rootDir, "local.properties")
        if (localPropertiesFile.exists()) {
            cargoExtension.localProperties.load(localPropertiesFile.inputStream())
        }

        if (cargoExtension.module == null) {
            throw GradleException("module cannot be null")
        }

        if (cargoExtension.libname == null) {
            throw GradleException("libname cannot be null")
        }

        // Allow to set targets, including per-project, in local.properties.
        val localTargets: String? =
            cargoExtension.localProperties.getProperty("rust.targets.${project.name}")
                ?: cargoExtension.localProperties.getProperty("rust.targets")
        if (localTargets != null) {
            cargoExtension.targets = localTargets.split(',').map { it.trim() }
        }

        if (cargoExtension.targets == null) {
            throw GradleException("targets cannot be null")
        }

        // Ensure that an API level is specified for all targets
        val apiLevel = cargoExtension.apiLevel
        if (cargoExtension.apiLevels.isNotEmpty()) {
            if (apiLevel != null) {
                throw GradleException("Cannot set both `apiLevel` and `apiLevels`")
            }
        } else {
            val default = apiLevel ?: project.extensions[Common::class].defaultConfig.minSdk!!
            cargoExtension.apiLevels = cargoExtension.targets!!.associateWith { default }
        }
        val missingApiLevelTargets = cargoExtension.targets!!.toSet().minus(
            cargoExtension.apiLevels.keys
        )
        if (missingApiLevelTargets.isNotEmpty()) {
            throw GradleException("`apiLevels` missing entries for: $missingApiLevelTargets")
        }

        project.extensions[Common::class].apply {
            sourceSets.getByName("main").jniLibs.directories.add(
                File(
                    project.layout.buildDirectory.asFile.get(),
                    "/rustJniLibs/android"
                ).absolutePath
            )
            sourceSets.getByName("test").resources.directories.add(
                File(
                    project.layout.buildDirectory.asFile.get(),
                    "/rustJniLibs/desktop"
                ).absolutePath
            )
        }

        // Determine the NDK version, if present
        val ndk = project.extensions[Component::class].sdkComponents.ndkDirectory.get().asFile.let {
            val ndkSourceProperties = Properties()
            val ndkSourcePropertiesFile = File(it, "source.properties")
            if (ndkSourcePropertiesFile.exists()) {
                ndkSourceProperties.load(ndkSourcePropertiesFile.inputStream())
            }
            val ndkVersion = ndkSourceProperties.getProperty("Pkg.Revision", "0.0")
            Ndk(path = it, version = ndkVersion)
        }

        // Fish linker wrapper scripts from our Java resources.
        val generateLinkerWrapper = project.rootProject.tasks.maybeCreate(
            "generateLinkerWrapper",
            GenerateLinkerWrapperTask::class.java
        ).apply {
            group = RUST_TASK_GROUP
            description = "Generate shared linker wrapper script"
        }

        generateLinkerWrapper.apply {
            // From https://stackoverflow.com/a/320595.
            from(project.zipTree(File(RustAndroidPlugin::class.java.protectionDomain.codeSource.location.toURI()).path))
            include("**/linker-wrapper*")
            into(File(project.layout.buildDirectory.get().asFile, "linker-wrapper"))
            eachFile {
                this.path = this.path.replaceFirst("com/nishtahir", "")
            }
            filePermissions {
                with(this@filePermissions) {
                    user {
                        read = true
                        write = true
                        execute = true
                    }
                    group {
                        read = true
                        execute = true
                    }
                    other {
                        read = true
                        execute = true
                    }
                }
            }
            includeEmptyDirs = false
            duplicatesStrategy = DuplicatesStrategy.EXCLUDE
        }

        val buildTask = project.tasks.maybeCreate(
            "cargoBuild",
            DefaultTask::class.java
        ).apply {
            group = RUST_TASK_GROUP
            description = "Build library (all targets)"
        }

        cargoExtension.targets!!.forEach { target ->
            val theToolchain = toolchains
                .filter { it.type == ToolchainType.ANDROID_PREBUILT }
                .find { it.platform == target }
            if (theToolchain == null) {
                throw GradleException(
                    "Target $target is not recognized (recognized targets: ${
                        toolchains.map { it.platform }.sorted()
                    }).  Check `local.properties` and `build.gradle`."
                )
            }

            val targetBuildTask = project.tasks.maybeCreate(
                "cargoBuild${target.replaceFirstChar { it.lowercase() }}",
                CargoBuildTask::class.java
            ).apply {
                group = RUST_TASK_GROUP
                description = "Build library ($target)"
                toolchain = theToolchain
                this.ndk = ndk
            }

            targetBuildTask.dependsOn(generateLinkerWrapper)
            buildTask.dependsOn(targetBuildTask)
        }
    }
}
