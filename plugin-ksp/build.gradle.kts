plugins {
    id("authserver.kotlin-conventions")
}

description = "KSP processor generating authserver-plugin.json from @AuthServerPlugin (Kotlin plugins)"

dependencies {
    implementation(project(":plugin-processor"))
    compileOnly(libs.ksp.api)
}
