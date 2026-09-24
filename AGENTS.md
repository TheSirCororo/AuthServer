# Repository Guidelines

## Project Structure & Module Organization

AuthServer is a Kotlin/JVM Minecraft authentication limbo (Java 1.8 - 26.3) built with Gradle Kotlin DSL.
Shared build logic lives in `build-logic/` (convention plugins) and versions in `gradle/libs.versions.toml`.

- `protocol/`: version-independent packets, codecs, packet ID tables (generated `packet-ids.txt`).
- `gamedata/`: generated per-version registries, tags and block/item mappings.
- `world/`: world model, map loaders (schematics, structures, Anvil) and chunk encoding.
- `storage/`: account database (SQLite, H2, PostgreSQL) and password hashing.
- `bridge/`: signed auth server -> proxy messages (plain Java).
- `api/`: public plugin API; `server/`: the application; `velocity/`: the Velocity plugin (Java).
- `plugin-processor/`, `plugin-ksp/`: generate `authserver-plugin.json` from `@AuthServerPlugin` (Java APT, KSP).
- `examples/example-plugin/`: example plugin, also loaded by the server tests.
- `tools/probe/` and `tools/vanilla/`: verification client and scripts that regenerate data from official servers.
- `tools/velocity/e2e.py`, `tools/stand/stand.py`: real-Velocity end-to-end test; local Velocity + auth + Paper stand.

Keep contracts in `api`, protocol details in `protocol`, and application logic in `server`. Tests mirror production
packages under each module's `src/test/`.

## Build, Test, and Development Commands

Use JDK 25 and the Gradle wrapper from the repository root:

- `./gradlew build`: compile all modules and run every test.
- `./gradlew :server:test`: end-to-end login tests against a real server on a free port.
- `./gradlew :server:shadowJar` / `:velocity:shadowJar`: runnable server jar / Velocity plugin.
- `java -jar server/build/libs/server-*-all.jar --config run/config.yml`: run locally.
- `tools/stand/stand.py up --accept-eula`: full local stand (Velocity on 25565) in `run/stand/`.
- `.github/workflows/ci.yml`: CI builds and tests; master also publishes the GHCR image, a tag and a release.

## Coding Style & Naming Conventions

Official Kotlin style, four-space indentation, `UpperCamelCase` types, `lowerCamelCase` members, packages under
`ru.cororo.authserver`. Packet models are named after what they do (`JoinGamePacket`, `ChatCommandPacket`);
version differences belong in the codec, keyed on `ProtocolVersion`. Never hand-edit generated resources
(`packet-ids.txt`, `gamedata/.../gamedata/**`, `world/src/test/resources/vanilla-chunks`); rerun `tools/vanilla`.

## Testing Guidelines

JUnit 5 with `kotlin-test`. Name classes `<Subject>Test`; Kotlin test methods must have block bodies (JUnit skips
methods that return values). Protocol changes must keep `probe verify` clean for every recorded release. Report
automated checks and any manual client verification.

## Commit & Pull Request Guidelines

Short, imperative commit messages without a mandatory prefix. PRs explain the change, affected modules and protocol
versions, and validation performed. Never commit client credentials or session caches (`SessionCache.ini`,
`ProfileKeyCache.ini`, `MinecraftClient.*`) or runtime data.
