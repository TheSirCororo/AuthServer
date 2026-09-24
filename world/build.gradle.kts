plugins {
    id("authserver.kotlin-conventions")
}

description = "World model, map loaders and per-version chunk encoding"

dependencies {
    api(project(":protocol"))
    api(project(":gamedata"))
    implementation(libs.slf4j.api)
}
