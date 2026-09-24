plugins {
    id("authserver.java-conventions")
    alias(libs.plugins.shadow)
}

description = "Velocity plugin routing players through the auth server"

dependencies {
    compileOnly(libs.velocity.api)
    annotationProcessor(libs.velocity.api)
    implementation(project(":bridge"))
    testImplementation(libs.velocity.api)
}

tasks.withType<JavaCompile>().configureEach {
    // Velocity's annotation processor writes velocity-plugin.json; its unrecognised-option warnings are noise.
    options.compilerArgs.add("-Xlint:-options")
}

tasks.shadowJar {
    archiveClassifier = ""
}

tasks.jar { enabled = false }
tasks.assemble { dependsOn(tasks.shadowJar) }
