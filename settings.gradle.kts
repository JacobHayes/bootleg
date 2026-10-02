pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io") { content { includeGroupByRegex("com\\.github\\.topjohnwu.*") } }
    }
}

rootProject.name = "bootleg"
include(":core", ":app")

// Phase 3 feasibility spike; it needs the gomobile AAR from `mise run remote:aar`, so
// `mise run check` still works without it.
if (file("remote/build/bootlegremote.aar").exists()) {
    include(":remotespike")
}
