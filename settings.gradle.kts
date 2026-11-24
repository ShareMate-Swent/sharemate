// Co-authored-by: OpenAI Codex <noreply@openai.com>
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
        // Maven Central rate-limits this large Robolectric artifact on hosted CI runners.
        maven("https://maven-central.storage-download.googleapis.com/maven2/") {
            content { includeModule("org.robolectric", "nativeruntime-dist-compat") }
        }
        mavenCentral()
    }
}

rootProject.name = "SampleApp"
include(":app")
 