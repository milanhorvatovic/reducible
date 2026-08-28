plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.kotlinter)
    alias(libs.plugins.androidKmpLibrary)
    alias(libs.plugins.kotlinSerialization)
}

kotlin {
    explicitApi()
    jvmToolchain(17)

    android {
        namespace = "io.github.milanhorvatovic.reducible.cookbook.notes"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()
        withHostTestBuilder {}
    }
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonMain.dependencies {
            api(projects.reducibleCore)
            api(projects.reducibleImmutable)
            api(libs.kotlinx.collections.immutable)
            implementation(libs.kotlinx.serialization.core)
        }
        commonTest.dependencies {
            implementation(projects.reducibleTest)
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.kotlinx.serialization.json)
        }
    }
}
