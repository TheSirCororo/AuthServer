plugins {
    `kotlin-dsl`
}

dependencies {
    implementation(libs.kotlin.gradle.plugin)
    implementation(libs.kotlin.serialization.plugin)
    implementation(libs.dokka.gradle.plugin)
    implementation(libs.ksp.gradle.plugin)
}
