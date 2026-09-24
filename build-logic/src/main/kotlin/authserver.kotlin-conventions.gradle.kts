// Kotlin modules: JVM conventions plus the Kotlin compiler and kotlin-test.
plugins {
    id("authserver.java-conventions")
    kotlin("jvm")
    kotlin("plugin.serialization")
}

kotlin {
    jvmToolchain(25)
    compilerOptions {
        allWarningsAsErrors = false
        freeCompilerArgs.add("-Xjsr305=strict")
    }
}

dependencies {
    testImplementation(kotlin("test-junit5"))
}
