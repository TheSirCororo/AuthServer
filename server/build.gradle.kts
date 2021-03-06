apply(plugin = "application")

dependencies {
    val ktor_version: String by project
    val logback_version: String by project

    api(project(":api"))
    implementation("io.ktor:ktor-client-cio:$ktor_version")
    implementation("io.netty:netty-all:4.1.77.Final")
    implementation("ch.qos.logback:logback-classic:$logback_version")
    implementation("org.bouncycastle:bcpkix-jdk15on:1.70")
    implementation("net.benwoodworth.knbt:knbt:0.11.1")
    implementation("com.google.code.gson:gson:2.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.3.3")
    implementation("net.kyori:adventure-api:4.10.1")
    implementation("net.kyori:adventure-text-serializer-gson:4.10.1")
    implementation("net.kyori:adventure-nbt:4.10.1")
    implementation(kotlin("stdlib"))
}

repositories {
    mavenCentral()
}
