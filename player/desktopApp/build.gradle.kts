import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm")
    application
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

application {
    mainClass.set("dev.chronotone.player.desktop.MainKt")
}

dependencies {
    implementation(project(":shared"))
    testImplementation(kotlin("test"))
}

tasks.test {
    // The screenshot test paints a panel to an image; no display should be needed or opened.
    systemProperty("java.awt.headless", "true")
}
