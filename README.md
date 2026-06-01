<div align="center">
 <h1>LibreLoginProd 🔐 - autologin plugin</h1>
  <p>Fork of the <b>LibreLogin</b> (previously LibrePremium) which has caused many problems with newest minecraft versions.
LibreLogin did not meet our expectations, which is why this fork was created.</p>
</div>

# About

LibreLoginProd is a production-ready authentication plugin for Minecraft servers. It handles premium (paid) player auto-login, session management, and Bedrock (Geyser/Floodgate) support. Built as a fork of LibreLogin with fixes for modern Minecraft versions including 1.21.11.

## Implementation

The plugin is split into two modules:
- **API** — public interfaces for integrating with LibreLoginProd from other plugins
- **Plugin** — core implementation supporting both Velocity proxy and Paper backend

Authentication data is stored in MySQL, PostgreSQL, or SQLite. Premium player verification is done via Mojang's session API. Geyser/Floodgate integration allows Bedrock players to authenticate seamlessly without a password.

# Quick information 📌

<img src="https://img.shields.io/badge/Java%20version-%2017+-blue?style=for-the-badge&logo=java&logoColor=white"
alt="Plugin requires Java 17 or newer"></img>
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

## Platforms ⚙️
- [✔️] Velocity - up to 1.21.11
- [✔️] Paper - up to 1.21.11
- [❌] BungeeCord - no longer supported, do not use it for production

## Main changes 

- [📚] Support for the newest Minecraft Paper and Velocity versions (1.21.11)
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
No, we use the same folder and config names as original the **LibreLogin**.

# License

Project is licensed under the Mozilla Public License 2.0.
[Read the license here.](https://github.com/Aelshi-nui/LibreLoginProd/blob/master/LICENSE)
