val ktor_version: String by project
val kotlin_version: String by project
val logback_version: String by project

plugins {
    application
    kotlin("jvm") version "1.6.10"
    kotlin("plugin.serialization") version "1.6.10"
    id("com.github.johnrengelman.shadow") version "7.1.2"
}

allprojects {
    apply(plugin = "kotlin")
    apply(plugin = "kotlinx-serialization")
    apply(plugin = "com.github.johnrengelman.shadow")

    group = "ru.cororo"
    version = "0.0.1"

//    application {
//        mainClass.set("ru.cororo.authserver.AuthServerMainKt")
//    }

    repositories {
        mavenCentral()
    }

    tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
        kotlinOptions {
            jvmTarget = "17"
            targetCompatibility = "17"
            sourceCompatibility = "17"
            freeCompilerArgs = listOf("-opt-in=kotlin.RequiresOptIn")
        }
    }
}