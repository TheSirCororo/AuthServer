rootProject.name = "AuthServer"

for (file in rootDir.walk()) {
    if (file.name == "build" || file.name == "buildSrc" || file.name.startsWith(".") || !file.isDirectory) continue
    val dir = ":${
        file.toRelativeString(rootDir)
            .replace(File.separator, ":")
    }"
    if (listOf("build.gradle", "build.gradle.kts").map {
            File(
                file,
                it
            )
        }.any { it.exists() }) {
        include(dir)
    }
}