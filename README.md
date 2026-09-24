# AuthServer

A lightweight Minecraft authentication server (limbo) written in Kotlin. Players join it first, log in or
register, and are then sent on to the real servers. It supports **every Java Edition release from 1.8 to 26.3**
natively, without ViaVersion.

- **Licensed and offline accounts.** Licensed players are authenticated through Mojang; offline ("cracked")
  players use `/register` and `/login`, as with AuthMe. By default new players choose their kind of login in a menu;
  premium name protection can instead keep offline players off names that belong to licensed accounts. An account
  never changes kind by itself: licensed accounts cannot log in with a password and password accounts cannot log in
  through Mojang.
- **Velocity integration.** A Velocity plugin decides online/offline mode at pre-login, keeps unauthenticated
  players in the limbo and routes them afterwards: random, least-players, round-robin or first server, with
  separate groups for licensed and offline players. Other Velocity plugins get events and an API.
- **Walkable maps.** Load a Sponge schematic (`.schem`), MCEdit schematic (`.schematic`), structure (`.nbt`) or an
  Anvil world. Blocks are translated per client version, so a 26.3 map works on 1.8: missing blocks become
  similar old ones (cherry stairs -> oak stairs, red concrete -> red wool), the way ViaBackwards does it.
- **Databases.** SQLite, H2 or PostgreSQL. Passwords are hashed with Argon2id.
- **Two-factor authentication and email.** Players choose for themselves: codes from an authenticator app (TOTP) or
  by email after the password. A linked email also recovers a forgotten password.
- **Account import** from AuthMe, LimboAuth, BungeeAuth, SimpleLogin, AuthSystem and xLogin. Their hashes (SHA256,
  BCRYPT, ARGON2, PBKDF2, Whirlpool, xAuth, nLogin SHA512, ...) are accepted and upgraded to Argon2id on login.
- **Items and menus.** A help compass in the hotbar explains how to log in; when both licensed and offline login
  are enabled, a menu lets new players choose. Plugins can give items and open chest menus on every version.
- **Plugins.** Jar plugins with events, commands, a scheduler, items, menus and account operations, described by an
  `@AuthServerPlugin` annotation like Velocity's `@Plugin`.

## Supported versions

1.8 - 1.8.9, 1.9 - 1.9.4, 1.10 - 1.10.2, 1.11 - 1.11.2, 1.12 - 1.12.2, 1.13 - 1.13.2, 1.14 - 1.14.4, 1.15 - 1.15.2,
1.16 - 1.16.5, 1.17 - 1.17.1, 1.18 - 1.18.2, 1.19 - 1.19.4, 1.20 - 1.20.6, 1.21 - 1.21.11, 26.1 - 26.1.2, 26.2, 26.3.

Velocity modern forwarding requires 1.13+ clients; use `bungeeguard` forwarding for older ones.

## Build and run

JDK 25 is required. The Gradle wrapper is checked in.

```sh
./gradlew build                  # compile everything and run all tests
./gradlew :server:shadowJar      # server/build/libs/server-<version>-all.jar
./gradlew :velocity:shadowJar    # velocity/build/libs/velocity-<version>.jar
java -jar server/build/libs/server-*-all.jar [--config path/to/config.yml]
```

On first start the server writes a documented `config.yml`, `messages/en.yml` and `messages/ru.yml` next to the
config. Messages use [MiniMessage](https://docs.advntr.dev/minimessage/format.html); the client's language picks the
file. Console commands: `help`, `list`, `kick`, `plugins`, `stop`, `import` (below) and
`auth <info|register|password|unregister|premium> <name> [value]`.

Player commands: `/register <password> <password>` (`/reg`), `/login <password>` (`/l`),
`/changepassword <old> <new>`, `/unregister <password>` and `/premium` (switch an account to licensed login).

### Two-factor authentication and email

Every player decides how to protect their account; the commands work in the limbo after logging in and, behind
Velocity, on every other server:

- `/2fa totp` shows a key for an authenticator app (Google Authenticator, Aegis, 2FAS, ...), `/2fa confirm <code>`
  turns it on. From then on the login asks for `/code <code>` after the password (or the Mojang login). Each code
  works once.
- `/email set <address> <password>` and `/email confirm <code>` link an address; `/2fa email` then sends login codes
  there instead.
- `/2fa off <code>` turns it off again - it needs a current code, so a hijacked session cannot. Changing the email
  needs the password. Five wrong passwords or codes lock these commands for five minutes.
- `/recover` mails a code for a forgotten password: `/recover <code> <new password> <new password>`. Accounts with
  an authenticator app still need its code afterwards.

Email needs an SMTP account in the `email` section of `config.yml`; without it `/email` and `/recover` are hidden
and authenticator apps still work. Codes are six digits, valid for `code-minutes`, at most one per `resend-seconds`
per account. Administrators can reset players in the console: `auth 2fa <name> off`, `auth email <name> <address|off>`.
Imports keep AuthMe's email addresses and the authenticator secrets of AuthMe and LimboAuth.

`authentication.licensed-login: false` turns licensed login off completely: everyone registers with a password,
`/premium` and the login menu disappear, and the Velocity plugin forces offline mode for every player.
`authentication.login-menu` and `authentication.help-item` control the join menu and the help item.

### Importing accounts

Stop the other plugin, then run in the auth server console:

```
import <authme|limboauth|bungeeauth|simplelogin|authsystem|xlogin> <jdbc-url|database file>
       [user=..] [password=..] [table=..] [name-column=..] [password-column=..] [overwrite] [dry-run] [plaintext]
```

The source is a JDBC URL (`jdbc:mysql://host/db`, `jdbc:postgresql://...`) or a SQLite/H2 file. AuthMe, LimboAuth
and BungeeAuth tables are known; for the others (and for renamed tables) the table and columns are detected or can
be given explicitly. Existing accounts are skipped unless `overwrite` is set; `dry-run` only reports what would
happen. Unrecognised password values are rejected; `plaintext` imports them as plain-text passwords, which are
hashed on the first login (only for plugins that really stored plain text).

### Standalone

Players connect straight to the auth server (`proxy.forwarding: NONE`). `authentication.premium-policy` decides
how names without an account authenticate:

- `MANUAL` (default): new players join without Mojang and choose in a menu (or with `/premium`). A password login
  means `/register`; a licensed login makes the player reconnect - 1.20.5+ clients are transferred back
  automatically - and that one connection goes through Mojang. Only a verified player gets a licensed account; if
  the licensed login fails, the player is back offline and chooses again.
- `OFFLINE`: everyone registers with a password.
- `AUTO`: names owned by a licensed Mojang account must log in through Mojang, others register.
- `ONLINE`: licensed players only.

Offline connections of 1.20.5+ clients are encrypted, so passwords never travel in clear text. With `transfer.host`
set, authenticated 1.20.5+ players are transferred to that server.

### Behind Velocity

1. In `velocity.toml`: `online-mode = true`, `player-info-forwarding-mode = "modern"` (or `"bungeeguard"`), a
   forwarding secret, the auth server in `[servers]` and `try = ["<auth server>"]`.
2. In the auth server's `config.yml`: `proxy.forwarding: VELOCITY` (or `LEGACY` for bungeeguard), the same
   secret in `proxy.secret`, and `api.enabled: true`. Keep the API port reachable only from the proxy.
3. Put the plugin jar into Velocity's `plugins/` and edit `plugins/authserver/config.toml`: auth server name,
   `api-url`, the same `secret`, and the `[routing]` groups.

At pre-login the plugin asks the auth server how the name authenticates and forces Velocity into online or offline
mode. Players land on the auth server; after they log in, the auth server sends a signed (HMAC-SHA256) message
through the player's connection and the plugin routes them. Unauthenticated players cannot switch servers, and a
kick from the auth server never falls back to another server. Until they log in they also have no proxy
permissions (whatever permission plugin is installed - LuckPerms or another - its permissions are wrapped) and the
proxy runs none of their commands (they go to the auth server): before the password check an offline player is just
a name anyone can type. Proxy plugins that react to players in other ways (chat relays, join messages) should check
`AuthServerApi.get().isAuthenticated(player)`. With `premium-bypass = true` licensed players skip
the auth server entirely. When a player picks the licensed login (`MANUAL` policy), the plugin transfers 1.20.5+
clients back through the proxy if `licensed-transfer = true` (this needs `accepts-transfers = true` in the
`[advanced]` section of `velocity.toml`); other players are asked to reconnect.

On every other server the plugin adds `/changepassword <old> <new>` (`/cp`), which asks for confirmation with
`/changepassword confirm`, `/logout`, which sends the player back to the auth server, and `/2fa` and `/email`. They go
through the auth server's HTTP API; on the auth server itself the auth server's own commands are used.

### Test stand

`tools/stand/stand.py up --accept-eula` builds the project and starts Velocity (port 25565) in front of the auth
server (with the example plugin) and a Paper server as the lobby, all in `run/stand/`. Configs are written once and
can be edited; jars are refreshed on every start. Type `auth <command>`, `paper <command>` or `velocity <command>`
in its console; `stop` or Ctrl+C shuts everything down, `tools/stand/stand.py clean` deletes the stand.
`--paper <version>` picks the Paper version (default: the newest; clients must match it, as there is no
ViaVersion on the stand).

### Docker

```sh
./gradlew :server:shadowJar && docker build -t authserver .
docker run -it -p 25565:25565 -v authserver-data:/data authserver
```

`/data` holds `config.yml`, the database and `plugins/`. CI publishes the image as
`ghcr.io/thesircororo/authserver` (`latest` and one tag per build).

## Plugin API

Auth server plugins are jars in `plugins/`. The main class is annotated with `@AuthServerPlugin`, and an
annotation processor generates the `authserver-plugin.json` descriptor at build time: `plugin-ksp` for Kotlin (KSP),
`plugin-processor` for Java (`annotationProcessor`). Inside this repository the `authserver.plugin-conventions`
Gradle plugin sets up Kotlin, the API and KSP; `examples/example-plugin` is a complete plugin.

```kotlin
@AuthServerPlugin(id = "welcome", name = "Welcome", version = "1.0", authors = ["you"],
    dependencies = [Dependency("economy", optional = true)])
class WelcomePlugin : Plugin() {
    override fun onEnable() {
        server.events.on<PlayerAuthenticatedEvent>(this) { event ->
            event.player.sendMessage(Component.text("Hello, ${event.player.username}!"))
            if (event.mode == AuthMode.OFFLINE) event.targetServer = "offline-lobby" // override routing
        }
        server.commands.register(this, Command(name = "rules", executor = { source, _ ->
            source.sendMessage(Component.text("Be nice."))
        }))
    }
}
```

Events: `PreLoginEvent` (deny, or choose online/offline mode), `PlayerJoinEvent`, `PlayerRegisterEvent`
(cancellable), `PlayerLoginAttemptEvent`, `PlayerAuthenticatedEvent`, `PlayerAuthFailedEvent`, `PlayerChatEvent`,
`PlayerCommandEvent`, `PlayerMoveEvent`, `PlayerUseItemEvent`, `PlayerPluginMessageEvent`, `PlayerQuitEvent`,
`ServerStartedEvent`, `ServerStoppingEvent`. `player.setHotbarItem(slot, Item(...))` gives items and
`player.openMenu(Menu(title, rows))` opens chest menus with click handlers. Players are Adventure audiences: chat, action bars, titles, boss bars and the tab list work
on every version. `server.auth` registers accounts, changes passwords, toggles licensed login and force-logs players
in; `server.scheduler` runs tasks.

### Velocity plugin API

Velocity plugins that depend on `authserver` receive `AuthServerPreLoginEvent` (override the online/offline
decision), `AuthServerLoginEvent` (mode, method, and a changeable target server) and `AuthServerFailEvent`, and can
query `AuthServerApi.get().session(player)`.

## Modules

| Module | Contents |
| --- | --- |
| `protocol` | Version-independent packet models, codecs for 1.8 - 26.3, packet ID tables |
| `gamedata` | Per-version block/item mappings, registries and tags generated from official servers |
| `world` | World model, map loaders and chunk encoding for every chunk format |
| `storage` | Account database and password hashing |
| `bridge` | Signed auth server -> proxy messages (dependency-free Java) |
| `api` | Public API for auth server plugins |
| `server` | The application: networking, login, limbo, commands, plugins, HTTP API |
| `velocity` | The Velocity plugin (Java) |
| `plugin-processor`, `plugin-ksp` | Annotation processors generating plugin descriptors (Java APT, KSP) |
| `examples/example-plugin` | Example auth server plugin |
| `tools/probe` | Test client that records vanilla traffic and verifies our codecs against it |
| `tools/vanilla` | Scripts that download official servers and regenerate game data |
| `tools/velocity`, `tools/stand` | End-to-end test with a real Velocity proxy; local Velocity + auth + Paper stand |

## How version support is verified

Game data and packet IDs are never written by hand. `tools/vanilla` downloads every official server release
(SHA-1 checked against Mojang's manifest) and:

- builds the packet ID table from Mojang's `packets.json` reports (1.21+), the server's own protocol tables read
  through Mojang's mappings (1.14.4 - 1.20.4) and the archived wiki.vg protocol pages (1.8 - 1.20.6); sources are
  cross-checked wherever they overlap;
- maps blocks and items by upgrading every state of every release with Mojang's DataFixer, the code the game
  uses to open old worlds, and inverting the result;
- records a real vanilla session of every release with the probe client. `probe verify` decodes every captured
  packet with our codecs (no leftover bytes allowed) and re-encodes the lossless ones for a byte-exact comparison;
  registries and tags are taken from the same recordings.

Chunk encoding is tested against real vanilla chunks of every release, and `tools/velocity/e2e.py` runs a real
Velocity proxy with the plugin in front of two auth servers.

```sh
python3 tools/vanilla/fetch.py                           # download official servers into ~/.cache/authserver
python3 tools/vanilla/reports.py --java <jdk25>/bin/java # run Mojang's data generator
python3 tools/vanilla/packet_table.py --wiki <cache> --java <jdk25>/bin/java
./gradlew :probe:installDist
python3 tools/vanilla/probe.py --accept-eula             # starts throw-away vanilla servers
python3 tools/vanilla/gamedata.py --java <jdk25>/bin/java
python3 tools/vanilla/chunk_fixtures.py
```

Running vanilla servers means accepting the [Minecraft EULA](https://aka.ms/MinecraftEULA), hence the explicit flag.

## Limitations

- The limbo world is static: blocks cannot be broken or placed, and there are no entities or signs. Items have a
  name and lore only (no enchantments or other components).
- Velocity is the only proxy with a plugin; BungeeCord works for forwarding but has no routing plugin.
