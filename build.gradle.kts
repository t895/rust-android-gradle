buildscript {
    repositories {
        google()
        maven {
            url = uri("https://plugins.gradle.org/m2/")
        }
    }
    dependencies {
        classpath(libs.plugins.android.gradle.get().toString())
        classpath(libs.plugins.kotlin.gradle.get().toString())
    }
}

subprojects {
    repositories {
        google()
        maven {
            url = uri("https://plugins.gradle.org/m2/")
        }
    }
}

tasks.register<Delete>("clean") {
    delete(project.layout.buildDirectory)
}

tasks.wrapper {
    distributionType = Wrapper.DistributionType.ALL
}
