plugins {
    id("authserver.kotlin-conventions")
}

description = "Per-version game data: block/item mappings, registries and tags generated from official server data"

dependencies {
    api(project(":protocol"))
}
