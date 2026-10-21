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
    }
}

rootProject.name = "chronotone-player"

include(":shared")
include(":desktopApp")

// The Android app needs an SDK; the shared core and the desktop player do not. A checkout
// on a machine without Android tooling still gets a buildable, tested increment.
val androidSdk: String? = file("local.properties")
    .takeIf { it.isFile }
    ?.let { file -> java.util.Properties().apply { file.inputStream().use(::load) } }
    ?.getProperty("sdk.dir")
    ?.trim()
    ?: System.getenv("ANDROID_HOME")
    ?: System.getenv("ANDROID_SDK_ROOT")

if (androidSdk != null && file(androidSdk).isDirectory) {
    include(":androidApp")
} else {
    logger.lifecycle("No Android SDK found — building the shared core and the desktop player only.")
}
