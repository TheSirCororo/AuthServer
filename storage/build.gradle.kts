plugins {
    id("authserver.kotlin-conventions")
}

description = "Account storage (SQLite, H2, PostgreSQL) and password hashing"

dependencies {
    api(libs.slf4j.api)
    implementation(libs.hikari)
    implementation(libs.password4j)
    runtimeOnly(libs.sqlite.jdbc)
    runtimeOnly(libs.h2)
    runtimeOnly(libs.postgresql)
    // Only used to import accounts from other plugins, which mostly keep them in MySQL/MariaDB.
    runtimeOnly(libs.mariadb)
    testImplementation(libs.sqlite.jdbc)
    testImplementation(libs.h2)
    testImplementation(libs.junit.jupiter.params)
    testImplementation(libs.password4j)
}
