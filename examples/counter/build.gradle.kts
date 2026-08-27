plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.kotlinter)
    alias(libs.plugins.androidKmpLibrary)
}

kotlin {
    explicitApi()
    jvmToolchain(17)

    android {
        namespace = "io.github.milanhorvatovic.reducible.examples.counter"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()
        withHostTestBuilder {}
    }
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonMain.dependencies {
            api(projects.reducibleRuntime)
        }
        commonTest.dependencies {
            implementation(projects.reducibleTest)
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
