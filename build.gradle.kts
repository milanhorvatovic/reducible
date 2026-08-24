import io.gitlab.arturbosch.detekt.extensions.DetektExtension
import java.time.Duration

plugins {
    alias(libs.plugins.androidApplication) apply false
    alias(libs.plugins.androidKmpLibrary) apply false
    alias(libs.plugins.composeCompiler) apply false
    alias(libs.plugins.detekt) apply false
    alias(libs.plugins.kotlinJvm) apply false
    alias(libs.plugins.kotlinMultiplatform) apply false
    alias(libs.plugins.kotlinSerialization) apply false
    alias(libs.plugins.kotlinter) apply false
    alias(libs.plugins.skie) apply false
    alias(libs.plugins.binaryCompatibilityValidator)
}

val libraryProjects = subprojects.filter { it.path.startsWith(":reducible-") }

apiValidation {
    // Only the published modules have an API to keep; the examples are consumers of it.
    ignoredProjects += (subprojects - libraryProjects.toSet()).map { it.name }

    // Klib validation covers the iOS targets; the classic dump covers JVM/Android.
    @OptIn(kotlinx.validation.ExperimentalBCVApi::class)
    klib {
        enabled = true
    }
}

// detekt carries the style decisions ktlint has no rule for: braces on every `if`
// and multi-line `when` branch, and the repository's own rules from :detekt-rules
// (no implicit `it`; in Compose modules, slots passed by name). Every Kotlin
// project gets it, over all of `src`, without the default rule set; the Android
// app adds the Compose config.
val detektConfig = layout.projectDirectory.file("config/detekt/detekt.yml")
val composeDetektConfig = layout.projectDirectory.file("config/detekt/compose.yml")
subprojects {
    fun applyDetekt(compose: Boolean) {
        apply(plugin = "io.gitlab.arturbosch.detekt")
        dependencies.add("detektPlugins", project(":detekt-rules"))
        configure<DetektExtension> {
            buildUponDefaultConfig = false
            config.setFrom(if (compose) listOf(detektConfig, composeDetektConfig) else listOf(detektConfig))
            source.setFrom(layout.projectDirectory.dir("src"))
        }
        // detekt 1.23 knows JVM targets up to 22 and would otherwise take the daemon's.
        tasks.withType<io.gitlab.arturbosch.detekt.Detekt>().configureEach {
            jvmTarget = "17"
        }
    }
    pluginManager.withPlugin("org.jetbrains.kotlin.multiplatform") { applyDetekt(compose = false) }
    pluginManager.withPlugin("com.android.application") { applyDetekt(compose = true) }
}

// The simulator test binaries are most of a build's wall time. `-PskipIosTests` keeps the JVM
// host tests for the local inner loop; CI runs everything. The link and compile tasks are
// skipped too, since a skipped test task would still build its binary as a dependency. An
// onlyIf predicate rather than `enabled`, which the Kotlin plugin reassigns on native tasks.
val skipIosTests = providers.gradleProperty("skipIosTests").isPresent
subprojects {
    // A test that never suspends cannot be pre-empted by runTest's timeout, and a Kotlin/Native
    // test binary has none of its own: one drained-forever scheduler ran for twenty minutes
    // before it was killed by hand. Gradle's task timeout is the bound the scheduler cannot
    // provide; a full module's suite runs in well under a minute.
    tasks.withType<AbstractTestTask>().configureEach {
        timeout.set(Duration.ofMinutes(10))
    }
    tasks
        .matching { task ->
            task.name == "iosSimulatorArm64Test" ||
                task.name == "linkDebugTestIosSimulatorArm64" ||
                task.name == "compileTestKotlinIosSimulatorArm64"
        }.configureEach {
            // A local copy: the predicate is serialized by the configuration cache and must not
            // capture the build script.
            val skip = skipIosTests
            onlyIf("-PskipIosTests was passed") { !skip }
        }
}
