plugins {
    // Kotlin, the API as compileOnly (the server provides it, Adventure and the Kotlin runtime) and KSP,
    // which generates authserver-plugin.json from @AuthServerPlugin.
    id("authserver.plugin-conventions")
}

description = "Example AuthServer plugin; drop the jar into the server's plugins/ directory"
