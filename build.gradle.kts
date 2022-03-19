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

    dependencies {
//        compileOnly("com.velocitypowered:velocity-api:3.0.0")

        implementation("io.ktor:ktor-server-core:$ktor_version")
        implementation("io.ktor:ktor-server-netty:$ktor_version")
        implementation("io.ktor:ktor-client-cio:$ktor_version")
        implementation("io.ktor:ktor-network:$ktor_version")
        implementation("ch.qos.logback:logback-classic:$logback_version")
        implementation("org.bouncycastle:bcpkix-jdk15on:1.70")
        implementation("net.benwoodworth.knbt:knbt:0.11.1")
        implementation("com.google.code.gson:gson:2.9.0")
        implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.3.2")
        testImplementation("io.ktor:ktor-server-tests:$ktor_version")
        testImplementation("org.jetbrains.kotlin:kotlin-test:$kotlin_version")
        implementation(kotlin("stdlib"))
    }

    tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
        kotlinOptions {
            jvmTarget = "17"
            targetCompatibility = "17"
            sourceCompatibility = "17"
        }
    }
}