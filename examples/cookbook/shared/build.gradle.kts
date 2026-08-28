import org.jetbrains.kotlin.gradle.plugin.mpp.apple.XCFramework

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.kotlinter)
    alias(libs.plugins.androidKmpLibrary)
    alias(libs.plugins.skie)
}

skie {
    analytics {
        enabled.set(false)
    }
    features {
        // Only the wiring's factories are called from Swift with defaults; enabling this for the
        // whole library generated overload sets for core factories Swift never calls.
        group("io.github.milanhorvatovic.reducible.cookbook.shared") {
            co.touchlab.skie.configuration.DefaultArgumentInterop.Enabled(true)
        }
    }
}

kotlin {
    explicitApi()
    jvmToolchain(17)

    android {
        namespace = "io.github.milanhorvatovic.reducible.cookbook.shared"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()
        withHostTestBuilder {}
    }

    val xcframework = XCFramework("Shared")
    listOf(
        iosArm64(),
        iosSimulatorArm64(),
    ).forEach { target ->
        target.binaries.framework {
            baseName = "Shared"
            isStatic = true
            export(projects.reducibleCore)
            export(projects.reducibleRuntime)
            export(projects.examples.cookbook.featureNotes)
            export(projects.examples.cookbook.featureSession)
            export(projects.examples.cookbook.featureSettings)
            export(projects.examples.cookbook.featureRecipes)
            export(libs.kotlinx.collections.immutable)
            xcframework.add(this)
        }
    }

    sourceSets {
        commonMain.dependencies {
            api(projects.reducibleCore)
            api(projects.reducibleRuntime)
            api(projects.examples.cookbook.featureNotes)
            api(projects.examples.cookbook.featureSession)
            api(projects.examples.cookbook.featureSettings)
            api(projects.examples.cookbook.featureRecipes)
            api(libs.kotlinx.collections.immutable)
            // The debug log's round-trip check encodes and decodes feature states.
            implementation(libs.kotlinx.serialization.core)
            implementation(libs.kotlinx.serialization.json)
        }
        commonTest.dependencies {
            implementation(projects.reducibleTest)
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
