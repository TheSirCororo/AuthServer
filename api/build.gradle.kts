plugins {
    `java-library`
}

tasks.shadowJar {
    finalizedBy(tasks.javadoc, tasks.kotlinSourcesJar)
}

dependencies {
//        compileOnly("com.velocitypowered:velocity-api:3.0.0")
    val ktor_version: String by project
    val kotlin_version: String by project
    val logback_version: String by project

    compileOnlyApi("io.ktor:ktor-server-core:$ktor_version")
    compileOnlyApi("io.ktor:ktor-server-netty:$ktor_version")
    compileOnlyApi("io.ktor:ktor-client-cio:$ktor_version")
    compileOnlyApi("io.ktor:ktor-network:$ktor_version")
    compileOnlyApi("ch.qos.logback:logback-classic:$logback_version")
    compileOnlyApi("org.bouncycastle:bcpkix-jdk15on:1.70")
    compileOnlyApi("net.benwoodworth.knbt:knbt:0.11.1")
    compileOnlyApi("com.google.code.gson:gson:2.9.0")
    compileOnlyApi("org.jetbrains.kotlinx:kotlinx-serialization-json:1.3.2")
    compileOnlyApi("net.kyori:adventure-api:4.10.1")
    compileOnlyApi("net.kyori:adventure-text-serializer-gson:4.10.1")
    compileOnlyApi(kotlin("stdlib"))
    compileOnlyApi("net.kyori:adventure-nbt:4.10.1")
    testImplementation("io.ktor:ktor-server-tests:$ktor_version")
    testImplementation("org.jetbrains.kotlin:kotlin-test:$kotlin_version")
}