plugins {
    base
    // Kotlin and Dokka plugins come from build-logic; the rest is loaded once here for all modules.
    alias(libs.plugins.shadow) apply false
}
