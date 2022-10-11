apply(plugin = "application")

dependencies {
    val ktor_version: String by project
    val logback_version: String by project

    api(project(":api"))
    implementation("io.ktor:ktor-client-cio:$ktor_version")
    implementation("io.netty:netty-all:4.1.81.Final")
    implementation("ch.qos.logback:logback-classic:$logback_version")
    implementation("org.bouncycastle:bcpkix-jdk15on:1.70")
    implementation("net.benwoodworth.knbt:knbt:0.11.2")
    implementation("com.google.code.gson:gson:2.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.4.0")
    implementation("net.kyori:adventure-api:4.11.0")
    implementation("net.kyori:adventure-text-serializer-gson:4.11.0")
    implementation("net.kyori:adventure-nbt:4.11.0")
    implementation(kotlin("stdlib"))
}

repositories {
    mavenCentral()
}
