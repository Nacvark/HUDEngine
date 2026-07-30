# Notices / Уведомления

## Configuration format compatibility / Совместимость формата конфигурации

**EN.** HUDEngine reads a configuration layout (`huds/`, `layouts/`, `images/`, `texts/`, `heads/`,
`compasses/`) that is compatible with [BetterHud](https://github.com/toxicity188/BetterHud) by
toxicity188, distributed under the MIT License. Compatibility is deliberate: it lets server owners
migrate an existing HUD without rewriting their configs.

HUDEngine is an independent implementation and shares no source code with BetterHud. Where the two
projects necessarily agree — the resource pack encoding that both must produce for the vanilla
Minecraft client to render a HUD at all — that agreement follows from the client's own format, not
from copying.

**RU.** HUDEngine читает раскладку конфигурации (`huds/`, `layouts/`, `images/`, `texts/`, `heads/`,
`compasses/`), совместимую с [BetterHud](https://github.com/toxicity188/BetterHud) авторства
toxicity188, распространяемым по лицензии MIT. Совместимость сделана намеренно: она позволяет
владельцам серверов перенести существующий HUD без переписывания конфигов.

HUDEngine — независимая реализация и не содержит исходного кода BetterHud. Там, где два проекта
неизбежно совпадают — в кодировании ресурспака, которое оба обязаны выдавать, чтобы ванильный клиент
Minecraft вообще отрисовал HUD, — совпадение следует из формата самого клиента, а не из копирования.

## Bundled vanilla glyph data / Встроенные ванильные глифы

**EN.** The plugin ships glyph sheets (`ascii.png`, `nonlatin_european.png`, `accented.png`) and a
glyph metrics table extracted from the vanilla Minecraft client. They are used to make HUD text match
the font the player already sees. Minecraft and its assets are property of Mojang Studios and are
subject to the [Minecraft EULA](https://www.minecraft.net/eula) and the
[Minecraft Usage Guidelines](https://www.minecraft.net/usage-guidelines). HUDEngine is not an
official Minecraft product and is not approved by or associated with Mojang Studios or Microsoft.

**RU.** Плагин поставляется с листами глифов (`ascii.png`, `nonlatin_european.png`, `accented.png`) и
таблицей метрик, извлечёнными из ванильного клиента Minecraft. Они нужны, чтобы текст HUD совпадал со
шрифтом, который игрок и так видит. Minecraft и его ассеты принадлежат Mojang Studios и подпадают под
[Minecraft EULA](https://www.minecraft.net/eula) и
[Minecraft Usage Guidelines](https://www.minecraft.net/usage-guidelines). HUDEngine не является
официальным продуктом Minecraft, не одобрен Mojang Studios или Microsoft и не связан с ними.
