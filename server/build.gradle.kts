plugins {
    id("authserver.kotlin-conventions")
    application
    alias(libs.plugins.shadow)
}

description = "The auth server application"

application { mainClass = "ru.cororo.authserver.AuthServerMain" }

dependencies {
    implementation(project(":api"))
    implementation(project(":protocol"))
    implementation(project(":gamedata"))
    implementation(project(":world"))
    implementation(project(":storage"))
    implementation(project(":bridge"))
    implementation(libs.kaml)
    implementation(platform(libs.netty.bom))
    implementation(libs.netty.handler)
    implementation(libs.netty.transport)
    implementation(libs.adventure.text.serializer.gson)
    implementation(libs.adventure.text.serializer.plain)
    implementation(libs.gson)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.angus.mail)
    runtimeOnly(libs.logback.classic)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(project(":probe"))
    testImplementation(libs.sqlite.jdbc)
    testImplementation(libs.junit.jupiter.params)
    testImplementation(libs.greenmail.junit5)
}

tasks.withType<Jar>().configureEach {
    manifest.attributes(
        "Implementation-Version" to project.version,
        // SQLite loads its native library; without this JDK 22+ prints a warning.
        "Enable-Native-Access" to "ALL-UNNAMED",
    )
}

tasks.shadowJar {
    mergeServiceFiles()
    filesMatching("META-INF/*.kotlin_module") { duplicatesStrategy = DuplicatesStrategy.INCLUDE }
}

// The example plugin's jar is loaded by ExamplePluginTest like a real plugin.
val examplePlugin = configurations.create("examplePlugin") { isTransitive = false }
dependencies { examplePlugin(project(":example-plugin")) }
tasks.test {
    val jar = examplePlugin.incoming.files
    inputs.files(jar)
    jvmArgumentProviders.add(CommandLineArgumentProvider { listOf("-DexamplePluginJar=${jar.singleFile.absolutePath}") })
}
