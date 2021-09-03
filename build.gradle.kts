val ktor_version: String by project
val kotlin_version: String by project
val logback_version: String by project

plugins {
    application
    kotlin("jvm") version "1.5.30"
}

group = "ru.cororo"
version = "0.0.1"


repositories {
    mavenCentral()
    maven("https://nexus.velocitypowered.com/repository/maven-public/")
    maven("https://jitpack.io/")
}

dependencies {
    compileOnly("com.velocitypowered:velocity-api:3.0.0")

    implementation("io.ktor:ktor-server-core:$ktor_version")
    implementation("io.ktor:ktor-server-netty:$ktor_version")
    implementation("io.ktor:ktor-client-cio:$ktor_version")
    implementation("io.ktor:ktor-network:$ktor_version")
    implementation("ch.qos.logback:logback-classic:$logback_version")
    implementation("org.bouncycastle:bcpkix-jdk15on:1.69")
    implementation("net.benwoodworth.knbt:knbt:0.8.1")
    implementation("com.google.code.gson:gson:2.8.8")
    testImplementation("io.ktor:ktor-server-tests:$ktor_version")
    testImplementation("org.jetbrains.kotlin:kotlin-test:$kotlin_version")
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
    kotlinOptions {
        jvmTarget = "11"
    }
}