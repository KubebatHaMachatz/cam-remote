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

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "cam-remote"

// :core is a plain Kotlin/JVM module. Keeping it free of the Android plugin is what makes the
// "the core knows nothing about Android" rule a fact of the build graph rather than a convention:
// android.* simply is not on its compile classpath.
include(":core")
include(":app")
