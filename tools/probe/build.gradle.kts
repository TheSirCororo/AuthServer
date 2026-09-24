plugins {
    id("authserver.kotlin-conventions")
    application
}

description = "Test client that records vanilla server traffic and verifies our codecs against it"

application { mainClass = "ru.cororo.authserver.probe.ProbeMain" }

dependencies {
    implementation(project(":protocol"))
    implementation(libs.adventure.text.serializer.plain)
}
