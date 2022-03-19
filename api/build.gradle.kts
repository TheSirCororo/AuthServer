tasks.shadowJar {
    finalizedBy(tasks.javadoc, tasks.kotlinSourcesJar)
}