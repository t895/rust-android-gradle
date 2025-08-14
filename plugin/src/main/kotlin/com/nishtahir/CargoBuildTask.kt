package com.nishtahir

import org.apache.tools.ant.taskdefs.condition.Os
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.FileSystemOperations
import org.gradle.api.file.ProjectLayout
import org.gradle.api.logging.LogLevel
import org.gradle.api.provider.ProviderFactory
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.TaskAction
import java.io.File
import javax.inject.Inject

abstract class CargoBuildTask @Inject constructor(
    private val providerFactory: ProviderFactory,
    private val projectLayout: ProjectLayout,
    private val fileSystemOperations: FileSystemOperations
) : DefaultTask() {
    @Input
    var toolchain: Toolchain? = null

    @Input
    var ndk: Ndk? = null

    @TaskAction
    fun build(): Unit = with(project) {
        val extension = extensions[CargoExtension::class]
        // Need to capture the value to dereference smoothly.
        val toolchain = toolchain
        if (toolchain == null) {
            throw GradleException("toolchain cannot be null")
        }

        val ndk = ndk ?: throw GradleException("ndk cannot be null")

        buildProjectForTarget(
            toolchain,
            ndk,
            extension
        )
        // CARGO_TARGET_DIR can be used to force the use of a global, shared target directory
        // across all rust projects on a machine. Use it if it's set, otherwise use the
        // configured `targetDirectory` value, and fall back to `${module}/target`.
        //
        // We also allow this to be specified in `local.properties`, not because this is
        // something you should ever need to do currently, but we don't want it to ruin anyone's
        // day if it turns out we're wrong about that.
        val target =
            extension.localProperties.getProperty("rust.cargoTargetDir", "CARGO_TARGET_DIR")
                ?: extension.targetDirectory
                ?: "${extension.module!!}/target"

        val defaultTargetTriple = getDefaultTargetTriple(extension.rustcCommand)

        var cargoOutputDir = File(
            if (toolchain.target == defaultTargetTriple) {
                "${target}/${extension.profile}"
            } else {
                "${target}/${toolchain.target}/${extension.profile}"
            }
        )
        if (!cargoOutputDir.isAbsolute) {
            cargoOutputDir = File(projectLayout.projectDirectory.asFile, cargoOutputDir.path)
        }
        cargoOutputDir = cargoOutputDir.canonicalFile

        val intoDir = File(
            projectLayout.buildDirectory.get().asFile,
            "rustJniLibs/${toolchain.folder}"
        )
        intoDir.mkdirs()

        fileSystemOperations.copy {
            from(cargoOutputDir)
            into(intoDir)

            // Need to capture the value to dereference smoothly.
            val targetIncludes = extension.targetIncludes
            if (targetIncludes != null) {
                include(targetIncludes.asIterable())
            } else {
                // It's safe to unwrap, since we bailed at configuration time if this is unset.
                val libname = extension.libname!!
                include("lib${libname}.so")
                include("lib${libname}.dylib")
                include("${libname}.dll")
            }
        }
    }

    fun buildProjectForTarget(
        toolchain: Toolchain,
        ndk: Ndk,
        cargoExtension: CargoExtension
    ) {
        val apiLevel = cargoExtension.apiLevels[toolchain.platform]!!
        val defaultTargetTriple = getDefaultTargetTriple(cargoExtension.rustcCommand)

        val resultOutput = providerFactory.exec {
            val module = File(cargoExtension.module!!)
            workingDir = if (module.isAbsolute) {
                module
            } else {
                File(projectLayout.projectDirectory.asFile, module.path)
            }
            workingDir = workingDir.canonicalFile

            val theCommandLine = mutableListOf(cargoExtension.cargoCommand)

            if (!cargoExtension.rustupChannel.isEmpty()) {
                val hasPlusSign = cargoExtension.rustupChannel.startsWith("+")
                val maybePlusSign = if (!hasPlusSign) "+" else ""
                theCommandLine.add(maybePlusSign + cargoExtension.rustupChannel)
            }

            theCommandLine.add("build")

            // Respect `verbose` if it is set; otherwise, log if asked to
            // with `--info` or `--debug` from the command line.
            if (cargoExtension.verbose ?: logger.isEnabled(LogLevel.INFO)) {
                theCommandLine.add("--verbose")
            }

            val features = cargoExtension.featureSpec.features
            // We just pass this along to cargo as something space separated... AFAICT
            // you're allowed to have featureSpec with spaces in them, but I don't think
            // there's a way to specify them in the cargo command line -- rustc accepts
            // them if passed in directly with `--cfg`, and cargo will pass them to rustc
            // if you use them as default featureSpec.
            when (features) {
                is Features.All -> {
                    theCommandLine.add("--all-features")
                }

                is Features.DefaultAnd -> {
                    if (!features.featureSet.isEmpty()) {
                        theCommandLine.add("--features")
                        theCommandLine.add(features.featureSet.joinToString(" "))
                    }
                }

                is Features.NoDefaultBut -> {
                    theCommandLine.add("--no-default-features")
                    if (!features.featureSet.isEmpty()) {
                        theCommandLine.add("--features")
                        theCommandLine.add(features.featureSet.joinToString(" "))
                    }
                }

                else -> {}
            }

            if (cargoExtension.profile != "debug") {
                // Cargo is rigid: it accepts "--release" for release (and
                // nothing for dev).  This is a cheap way of allowing only
                // two values.
                theCommandLine.add("--${cargoExtension.profile}")
            }
            if (toolchain.target != defaultTargetTriple) {
                // Only providing --target for the non-default targets means desktop builds
                // can share the build cache with `cargo build`/`cargo test`/etc invocations,
                // instead of requiring a large amount of redundant work.
                theCommandLine.add("--target=${toolchain.target}")
            }

            // Target-specific environment configuration, passed through to
            // the underlying `cargo build` invocation.
            val toolchainTarget = toolchain.target.uppercase().replace('-', '_')
            val prefix = "RUST_ANDROID_GRADLE_TARGET_${toolchainTarget}_"

            // For ORG_GRADLE_PROJECT_RUST_ANDROID_GRADLE_TARGET_x_KEY=VALUE, set KEY=VALUE.
            logger.info("Passing through project properties with prefix '${prefix}' (environment variables with prefix 'ORG_GRADLE_PROJECT_${prefix}'")
            cargoExtension.localProperties.forEach { (key, value) ->
                val key = key.toString()
                val value = value.toString()
                if (key.startsWith(prefix)) {
                    val realKey = key.substring(prefix.length)
                    logger.debug(
                        "Passing through environment variable '{}' as '{}={}'",
                        key,
                        realKey,
                        value
                    )
                    environment(realKey, value)
                }
            }

            // Cross-compiling to Android requires toolchain massaging.
            if (toolchain.type != ToolchainType.DESKTOP) {
                val ndkPath = ndk.path
                val ndkVersionMajor = ndk.versionMajor

                val toolchainDirectory = if (toolchain.type == ToolchainType.ANDROID_PREBUILT) {
                    environment("CARGO_NDK_MAJOR_VERSION", ndkVersionMajor)

                    val hostTag = if (Os.isFamily(Os.FAMILY_WINDOWS)) {
                        if (Os.isArch("x86_64") || Os.isArch("amd64")) {
                            "windows-x86_64"
                        } else {
                            "windows"
                        }
                    } else if (Os.isFamily(Os.FAMILY_MAC)) {
                        "darwin-x86_64"
                    } else {
                        "linux-x86_64"
                    }
                    File("$ndkPath/toolchains/llvm/prebuilt", hostTag)
                } else {
                    cargoExtension.toolchainDirectory
                }

                val linkerWrapper =
                    if (System.getProperty("os.name").startsWith("Windows")) {
                        File(
                            projectLayout.buildDirectory.get().asFile,
                            "linker-wrapper/linker-wrapper.bat"
                        )
                    } else {
                        File(
                            projectLayout.buildDirectory.get().asFile,
                            "linker-wrapper/linker-wrapper.sh"
                        )
                    }
                environment("CARGO_TARGET_${toolchainTarget}_LINKER", linkerWrapper.path)

                val cc = File(toolchainDirectory, "${toolchain.cc(apiLevel)}").path;
                val cxx = File(toolchainDirectory, "${toolchain.cxx(apiLevel)}").path;
                val ar =
                    File(toolchainDirectory, "${toolchain.ar(apiLevel, ndkVersionMajor)}").path;

                // For build.rs in `cc` consumers: like "CC_i686-linux-android".  See
                // https://github.com/alexcrichton/cc-rs#external-configuration-via-environment-variables.
                environment("CC_${toolchain.target}", cc)
                environment("CXX_${toolchain.target}", cxx)
                environment("AR_${toolchain.target}", ar)

                // Set CLANG_PATH in the environment, so that bindgen (or anything
                // else using clang-sys in a build.rs) works properly, and doesn't
                // use host headers and such.
                val shouldConfigure = cargoExtension.getFlagProperty(
                    "rust.autoConfigureClangSys",
                    "RUST_ANDROID_GRADLE_AUTO_CONFIGURE_CLANG_SYS",
                    // By default, only do this for non-desktop platforms. If we're
                    // building for desktop, things should work out of the box.
                    toolchain.type != ToolchainType.DESKTOP
                )
                if (shouldConfigure) {
                    environment("CLANG_PATH", cc)
                }

                // Configure our linker wrapper.
                environment("RUST_ANDROID_GRADLE_PYTHON_COMMAND", cargoExtension.pythonCommand)
                environment(
                    "RUST_ANDROID_GRADLE_LINKER_WRAPPER_PY",
                    File(
                        projectLayout.buildDirectory.get().asFile,
                        "linker-wrapper/linker-wrapper.py"
                    ).path
                )
                environment("RUST_ANDROID_GRADLE_CC", cc)
                if (cargoExtension.generateBuildId) {
                    environment(
                        "RUST_ANDROID_GRADLE_CC_LINK_ARG",
                        "-Wl,--build-id,-soname,lib${cargoExtension.libname!!}.so"
                    )
                } else {
                    environment(
                        "RUST_ANDROID_GRADLE_CC_LINK_ARG",
                        "-Wl,-soname,lib${cargoExtension.libname!!}.so"
                    )
                }
            }

            cargoExtension.extraCargoBuildArguments?.let {
                theCommandLine.addAll(it)
            }

            commandLine = theCommandLine

            if (cargoExtension.exec != null) {
                (cargoExtension.exec!!)(this, toolchain)
            }
        }
        val result = resultOutput.result.get()
        logger.info(resultOutput.standardOutput.asText.get())
        result.assertNormalExitValue()
    }

    fun getDefaultTargetTriple(rustc: String): String? {
        val resultOutput = providerFactory.exec {
            commandLine = listOf(rustc, "--version", "--verbose")
        }
        val result = resultOutput.result.get()
        if (result.exitValue != 0) {
            logger.warn(
                "Failed to get default target triple from rustc (exit code: ${result.exitValue})"
            )
            return null
        }
        val output = resultOutput.standardOutput.asText.get()

        // The `rustc --version --verbose` output contains a number of lines like `key: value`.
        // We're only interested in `host: `, which corresponds to the default target triple.
        val triplePrefix = "host: "

        val triple = output.split("\n")
            .find { it.startsWith(triplePrefix) }?.substring(triplePrefix.length)?.trim()

        if (triple == null) {
            logger.warn("Failed to parse `rustc -Vv` output! (Please report a rust-android-gradle bug)")
        } else {
            logger.info("Default rust target triple: $triple")
        }
        return triple
    }
}
