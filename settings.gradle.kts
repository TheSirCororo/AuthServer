pluginManagement {
    includeBuild("build-logic")
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        mavenCentral()
        maven("https://repo.papermc.io/repository/maven-public/") {
            content { includeGroupAndSubgroups("com.velocitypowered") }
        }
    }
}

rootProject.name = "AuthServer"

include(
    "protocol",   // version-independent packets, codecs and per-version packet ID tables
    "gamedata",   // per-version game data: block/item mappings, registries, tags
    "world",      // world model, map loaders and per-version chunk encoding
    "storage",    // account database and password hashing
    "bridge",     // auth server <-> proxy message format (Java, dependency-free)
    "api",        // public API for auth server plugins
    "server",     // the auth server application
    "velocity",   // Velocity proxy plugin
    "plugin-processor", // generates authserver-plugin.json from @AuthServerPlugin (Java annotation processor)
    "plugin-ksp",       // the same for Kotlin plugins, as a KSP processor
)

// Example plugin showing the plugin API.
include(":example-plugin")
project(":example-plugin").projectDir = file("examples/example-plugin")

// Development tools, not part of the distribution.
include(":probe")
project(":probe").projectDir = file("tools/probe")
