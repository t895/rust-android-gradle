plugins {
    `kotlin-dsl`
}

group = "org.mozilla.rust-android-gradle"
version = libs.versions.pluginVersion.get()

gradlePlugin {
    plugins {
        create("rustAndroidGradlePlugin") {
            id = "org.mozilla.rust-android-gradle.rust-android"
            implementationClass = "com.nishtahir.RustAndroidPlugin"
            displayName = "Plugin for building Rust with Cargo in Android projects"
            description = "A plugin that helps build Rust JNI libraries with Cargo for use in Android projects."
            tags = listOf("rust", "cargo", "android")
        }
    }
}

kotlin {
    jvmToolchain(libs.versions.jvmToolchain.get().toInt())
}

dependencies {
    compileOnly(libs.plugins.android.gradle.get().toString())
    compileOnly(libs.plugins.kotlin.gradle.get().toString())
}
