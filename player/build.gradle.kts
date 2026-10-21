// Versions pinned to the combination proven in the sibling music-repo build: Kotlin 2.3.20
// with AGP 8.10.1 on Gradle 8.11.1.
plugins {
    kotlin("multiplatform") version "2.3.20" apply false
    kotlin("jvm") version "2.3.20" apply false
    kotlin("android") version "2.3.20" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.3.20" apply false
    id("com.android.application") version "8.10.1" apply false
    id("com.android.library") version "8.10.1" apply false
}
