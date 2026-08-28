pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
    }
}

enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

rootProject.name = "reducible"

// The library: every `reducible-*` project is published.
include(":reducible-core")
include(":reducible-runtime")
include(":reducible-test")
include(":reducible-immutable")
include(":reducible-optics-arrow")
include(":reducible-android")

// The repository's own detekt rules, loaded into every module's detekt task.
include(":detekt-rules")

// The examples consume the library by project path; they are never published.
include(":examples:counter")
include(":examples:koin")
include(":examples:cookbook:feature-notes")
include(":examples:cookbook:feature-session")
include(":examples:cookbook:feature-settings")
include(":examples:cookbook:feature-recipes")
include(":examples:cookbook:shared")
