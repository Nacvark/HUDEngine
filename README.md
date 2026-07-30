<div align="center">

<img src=".github/banner.png" alt="HUDEngine — build custom HUDs without client mods" width="100%">

[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)
[![Java 21](https://img.shields.io/badge/Java-21-orange.svg)](https://adoptium.net/)
[![Paper](https://img.shields.io/badge/Paper-1.21.4%20–%2026.2-brightgreen.svg)](https://papermc.io/)
[![bStats](https://img.shields.io/bstats/servers/32968)](https://bstats.org/plugin/bukkit/HUDEngine/32968)

🌎 Choose language: **English (EN)** · [Русский (RU)](README-RU.md)

</div>

---

**HUDEngine** is a plugin for Minecraft 1.21.4+ that lets Paper and Folia server owners build modern
HUD interfaces without asking players to install anything client-side. All a player needs is the
resource pack the plugin generates and the server sends.

HUDEngine reads YAML files and turns them into a set of glyphs placed at an enormous negative
`ascent`, which parks them far below the screen. A generated `rendertype_text` shader recognises
those glyphs and puts them back where they belong — and that is the HUD, with no client modification
involved. All of it is carried by a boss bar, and the player only has to accept the generated
resource pack.

## Plugin features

- **One resource pack for all supported versions.** Clients from Minecraft 1.21.4 to 26.2 pick the
  assets that apply to them automatically.
- **Easy to use.** A minimal folder structure and little that has to be configured. Everything about
  setting it up is in the documentation, and the open source fills in the implementation details.
- **Optimized performance.** HUDs are rebuilt only for the players who have them enabled. Rendering
  is cached on two levels, and when nothing has changed, no packet is sent at all.
- **Configuration errors are explained, not hidden.** The plugin names the exact file and
  configuration element behind the problem, and suggests a correction when it looks like a typo.
- **Vanilla HUD hiding.** Optionally hide vanilla interface elements: health, hunger, armor, level,
  experience bar, hotbar and more.
- **Built-in placeholder and condition engine.** Built-in values such as health and hunger are
  displayed with no external dependency, and the condition system tracks player state.
- **Open API.** Full control over the HUD from your own plugins. Particularly useful for the compass:
  personal markers, nearby player indicators, quest objectives and so on.
- **Advanced compass.** More than a direction — it supports markers, both static (set in files) and
  dynamic (driven through the API).
- **Compatible with BetterHud configurations.** Migration is as simple as copying the configuration
  folder, popups and animations aside, and HUDEngine-specific features can be added on top as needed.
- **Small resource pack size.** The generated ZIP is around 230 KB by default, so players are not
  kept waiting. Even a large HUD setup is unlikely to pass 1 MB.
- **Built-in resource pack hosting.** Serve the pack straight from the game server with no separate
  web server. The download message and whether a player is kicked for declining are both
  configurable.

## Requirements
Paper or Folia 1.21.4 — 26.2 (Java 21—25).
No third-party plugins needed.

## Building a HUD

The plugin generates a starter pack on first start: a player head, bars for health, hunger and air,
coordinates, and a compass. It is only a primitive example of what a HUD can be, and a set of configs
to take apart.

<img width="1920" height="1017" alt="2026-07-29_09 11 24" src="https://github.com/user-attachments/assets/a60f336c-40f5-4988-8601-027734646f8c" />

Where to take it from there is up to you. Everything needed to build a modern, detailed HUD is in the
plugin's wiki: [go there](https://github.com/Nacvark/HUDEngine/wiki).

<details>
<summary><b>🎥 An example of a production HUD</b></summary>

**▶️ Click the image to watch the demonstration on YouTube**

[![An example of a production HUD](https://img.youtube.com/vi/NZtoazEHZ2A/maxresdefault.jpg)](https://youtu.be/NZtoazEHZ2A)

</details>

## Getting the pack to players

The plugin builds the pack into `plugins/HUDEngine/build/HUDEngine.zip`. Getting it from there to a
player is the one decision you have to make, and there are three ways to make it.

```yaml
resource-pack:
  delivery: none   # none | url | host
```

**`host` — the server hands it out itself.** The usual answer, and the one to reach for on panel
hosting where you have a game server and nothing else. The plugin runs a small HTTP server, so no web
server, no file hosting, no upload after every edit:

```yaml
resource-pack:
  delivery: host
  host:
    bind: "0.0.0.0"
    port: 8123
    path: "/hudengine.zip"
    public-url: "http://your-server-address:8123/hudengine.zip"
```

`public-url` has to be the server address a player will knock on. Not `localhost` and not
`127.0.0.1` — put in the server's IP with a reachable port.

**Testing on your own PC?** A server on localhost is the one case where `localhost` in `public-url`
is correct. It works while you are testing and stops working the moment the server becomes reachable
by players.

**`url` — the pack is already published.** If you keep packs on your own hosting, a CDN, or anywhere
with a direct download link, put that link in `resource-pack.url` and the plugin sends it. The hash
is still computed from the file that was actually built, so it never goes stale.

**`none` — just build the file.** For servers that already distribute packs their own way. The plugin
writes the archive into `plugins/HUDEngine/build/` and does nothing else. How it reaches players is
left to you.

Whichever you pick: the pack is byte-identical between builds when the configuration has not changed,
so clients do not re-download something they already have. If you already send packs of your own,
list them under `extra-packs` and everything arrives in a single prompt. Full detail is on the
[Resource pack](https://github.com/Nacvark/HUDEngine/wiki/Resource-Pack) wiki page.

## Commands and permissions

Aliased to `/hud`. Every command is available to operators only by default.

| Command | Description | Permission |
|---|---|---|
| `/hudengine toggle [player]` | Turn the HUD on or off | `hudengine.command.toggle` |
| `/hudengine show <hud> [player]` | Add a HUD to someone's set | `hudengine.command.show` |
| `/hudengine hide <hud> [player]` | Remove one | `hudengine.command.hide` |
| `/hudengine reset [player]` | Back to the configured default | `hudengine.command.reset` |
| `/hudengine list [player]` | What exists, and what is visible | `hudengine.command.list` |
| `/hudengine reload` | Recompile the configuration | `hudengine.command.reload` |
| — | Running a command against another player | `hudengine.command.others` |
| — | All of the above | `hudengine.admin` |

`hudengine.command.others` covers naming another player in a command, and it is checked on top of
that command's own permission. That also makes it a small integration point: a cutscene plugin (or
anything else) that can run server commands can hide a player's HUD with `/hud toggle <player>`,
without touching the API.

## For developers

All of the code is open and released under the MIT licence. Read it
[here](https://github.com/Nacvark/HUDEngine).

| Module | Purpose |
|---|---|
| `hudengine-api` | Public API for other plugins. This is what you depend on. |
| `hudengine-core` | Compiler and renderer. JDK only. |
| `hudengine-paper` | Paper/Folia platform layer. |
| `hudengine-cli` | Standalone pack compiler. |
| `hudengine-plugin` | The distributable jar. |

The API is on Maven Central. To add it to your plugin:

```xml
<dependency>
    <groupId>io.github.nacvark</groupId>
    <artifactId>hudengine-api</artifactId>
    <version>1.0.0</version>
    <scope>provided</scope>
</dependency>
```

```kotlin
compileOnly("io.github.nacvark:hudengine-api:1.0.0")
```

Also add `softdepend: [HUDEngine]` to your `plugin.yml`, and get hold of the engine through the
Bukkit service registry:

```java
HudEngineProvider.find().ifPresent(hud ->
        hud.values().register("myplugin:mana", player -> String.valueOf(manaOf(player))));
```

`[myplugin:mana]` now works in any HUD pattern. Fuller examples — compass providers, showing a HUD
for a set time, measuring text — are on the
[API](https://github.com/Nacvark/HUDEngine/wiki/API) wiki page.

## License

MIT — see [LICENSE](LICENSE). Third-party notices in [NOTICE.md](NOTICE.md).

---

## Support the author

The plugin is entirely free, but you can support me: [DONATE.md](DONATE.md).

---
