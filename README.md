<div align="center">
 <h1>LibreLoginX 🔐 - autologin plugin</h1>
  <p>Fork of the <b>LibreLogin</b> (previously LibrePremium) which has caused many problems with newest minecraft versions.
LibreLogin did not meet our expectations, which is why this fork was created.</p>
</div>

# About

LibreLoginX is a production-ready authentication plugin for Minecraft servers. It handles premium (paid) player auto-login, session management, and Bedrock (Geyser/Floodgate) support. Built as a fork of LibreLogin with fixes for modern Minecraft versions including Paper/Folia 26.1.2.

## Implementation

The plugin is split into two modules:
- **API** — public interfaces for integrating with LibreLoginX from other plugins
- **Plugin** — core implementation supporting Velocity proxy, Paper, and Folia backends

Authentication data is stored in MySQL, PostgreSQL, or SQLite. Premium player verification is done via Mojang's session API. Geyser/Floodgate integration allows Bedrock players to authenticate seamlessly without a password.

# Quick information 📌

<img src="https://img.shields.io/badge/Java%20version-%2025+-blue?style=for-the-badge&logo=java&logoColor=white"
alt="Plugin requires Java 25 or newer"></img>
<img src="https://img.shields.io/badge/Current%20version-1.30.0-green?style=for-the-badge"
alt="Current plugin version is 1.30.0"></img>
<a href="https://github.com/Aelshi-nui/LibreLoginProd/graphs/contributors">
<img src="https://img.shields.io/badge/Contributors-Credits-blue?style=for-the-badge" 
alt="Contributors listed"></img>
</a>
<a href="https://github.com/Aelshi-nui/LibreLoginProd/wiki">
<img src="https://img.shields.io/badge/Documentation-Docs-orange?style=for-the-badge&logo=wikipedia" alt="Documentation on the Wiki"></img>
</a>

## Basic set of features 🎯

- AutoLogin for premium players
- TOTP 2FA (Authy, Google Authenticator...) [details](https://github.com/Aelshi-nui/LibreLoginProd/wiki/2FA)
- Session system
- Name validation (including case sensitivity check)
- Automatic data migration for premium players
- Migration of a player's data by using one command
- Geyser (Bedrock) support using [Floodgate](https://github.com/Aelshi-nui/LibreLoginProd/wiki/Floodgate)
- Folia login protection for unauthenticated players

## Platforms ⚙️
- [✔️] Velocity - up to 1.21.11
- [✔️] Paper - up to 26.1.2
- [✔️] Folia - up to 26.1.2
- [❌] BungeeCord - no longer supported, do not use it for production

## Main changes 

- [📚] Support for newer Paper/Folia builds (26.1.2)
- [📚] Version bumped to LibreLoginX 1.30.0
- [🛡️] Folia-safe unauthenticated player protection: blindness, invisibility, invulnerability, no collision, and blocked interactions
- [🌍] Folia can use a preloaded configured auth/limbo world, or fall back to protecting the player's original login location
- [🐛] Geyser issue fixed
- [❌] No more support for BungeeCord (maybe will be brought back in future)
- [❌] Removed compatibility with NanoLimboPlugin (should not be used on prod)

# Contributors, thanks to:

- **vuxeim** - for support for the newest minecraft versions
- **original LibreLogin creators** - for creating the LibreLogin

# FAQ

### What does prod mean?
This means that the project is a heavily modified version intended for production use.

### Why is the plugin almost 5MB?
We are currently trying to go down to 500KB, but first we need
to divide whole project into submodules.

### Will the folder name change after installation?
Yes. The plugin is now named **LibreLoginX**, so Bukkit/Paper will use `plugins/LibreLoginX`. Move your old `plugins/LibreLogin` files into `plugins/LibreLoginX` if you want to keep the same configuration and database.

### Did the admin command change?
No. The admin command is still `/librelogin`, for example `/librelogin user ...`. Permission nodes also remain `librelogin.*` for compatibility with existing setups.

### How does Folia auth world support work?
Folia does not allow LibreLoginX to create or load worlds at runtime. If the configured auth/limbo world is already loaded by the server or another plugin before LibreLoginX starts, LibreLoginX will use it. If it is not loaded, unauthenticated players stay at their login location with protection enabled until they log in or register.

# License

Project is licensed under the Mozilla Public License 2.0.
[Read the license here.](https://github.com/Aelshi-nui/LibreLoginProd/blob/master/LICENSE)
