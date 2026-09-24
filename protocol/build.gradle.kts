plugins {
    id("authserver.kotlin-conventions")
}

description = "Version-independent Minecraft packets, codecs and per-version packet ID tables"

dependencies {
    api(platform(libs.netty.bom))
    api(libs.netty.buffer)
    api(libs.netty.codec)
    api(platform(libs.adventure.bom))
    api(libs.adventure.api)
    api(libs.adventure.nbt)
    implementation(libs.adventure.text.serializer.gson)
    implementation(libs.adventure.text.serializer.legacy)
}
