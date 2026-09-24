// AuthServer plugins written in Kotlin: the API on the compile classpath (the server provides it at runtime)
// and KSP generating authserver-plugin.json from @AuthServerPlugin.
plugins {
    id("authserver.kotlin-conventions")
    id("com.google.devtools.ksp")
}

dependencies {
    "compileOnly"(project(":api"))
    "ksp"(project(":plugin-ksp"))
}
