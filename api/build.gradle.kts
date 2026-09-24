plugins {
    id("authserver.kotlin-conventions")
    id("authserver.dokka")
}

description = "Public API for auth server plugins"

java { withSourcesJar() }

dependencies {
    api(project(":protocol"))
    api(project(":bridge"))
    api(libs.kotlinx.coroutines.core)
    api(libs.slf4j.api)
    api(platform(libs.adventure.bom))
    api(libs.adventure.api)
    api(libs.adventure.text.minimessage)
}
