plugins {
    id("authserver.java-conventions")
}

description = "Annotation processor generating authserver-plugin.json from @AuthServerPlugin (Java plugins)"

dependencies {
    testImplementation(project(":api"))
}
