package ru.cororo.authserver.api.plugin

/**
 * Describes a plugin's main class, like Velocity's `@Plugin`. At compile time the `plugin-ksp` (Kotlin) or
 * `plugin-processor` (Java) processor turns it into the `authserver-plugin.json` descriptor, so plugins need no
 * hand-written JSON.
 *
 * ```kotlin
 * @AuthServerPlugin(id = "welcome", name = "Welcome", version = "1.0", authors = ["you"],
 *     dependencies = [Dependency("economy", optional = true)])
 * class WelcomePlugin : Plugin()
 * ```
 *
 * @property id lowercase letters, digits, `-` and `_`
 * @property name display name; defaults to [id]
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.BINARY)
@MustBeDocumented
annotation class AuthServerPlugin(
    val id: String,
    val name: String = "",
    val version: String = "",
    val description: String = "",
    val authors: Array<String> = [],
    val dependencies: Array<Dependency> = [],
)

/** A plugin that must be enabled first; [optional] ones are only ordered before this plugin when present. */
@Target()
@Retention(AnnotationRetention.BINARY)
annotation class Dependency(val id: String, val optional: Boolean = false)
