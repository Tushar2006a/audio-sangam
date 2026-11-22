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
        // NewPipe's link extractor is published on JitPack
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "AudioOnly"
include(":app")
