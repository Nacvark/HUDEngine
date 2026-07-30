Put .ttf and .otf files here, then name one in texts/ with `file:`.

A HUD font is rasterised at about 8 pixels tall. That is the size Minecraft's own
font is drawn at, and it is small: a letter is eight pixels from top to bottom, and
a stroke is one pixel wide or it is nothing. Fonts made for that size survive it.
Fonts made for a poster do not — a graffiti, script or heavily decorated face loses
the detail that made it worth choosing and comes out as a smudge. Nothing is broken
when that happens; there is simply nowhere to put the detail.

Pick a font with plain, even strokes: a monospace or a UI face. Consolas, JetBrains
Mono and DejaVu Sans Mono all work. If a font must be decorative, raise `scale` in
texts/ so it has more pixels to live in, and check it in game before committing to it.

The compile report says how many characters came from the file and lists any the
font could not draw at all.

-------------------------------------------------------------------------------

Кладите сюда файлы .ttf и .otf, затем укажите нужный в texts/ через `file:`.

Шрифт HUD растрируется примерно в 8 пикселей высотой. Это размер, которым рисуется
собственный шрифт Minecraft, и он маленький: буква занимает восемь пикселей сверху
донизу, а штрих либо шириной в пиксель, либо его нет вовсе. Шрифты, сделанные под
такой размер, это переживают. Шрифты для плаката — нет: граффити, рукописный или
богато украшенный шрифт теряет ровно те детали, ради которых его выбирали, и
превращается в кляксу. Это не поломка, просто детали некуда положить.

Берите шрифт с простыми ровными штрихами: моноширинный или интерфейсный. Consolas,
JetBrains Mono и DejaVu Sans Mono подходят. Если шрифт всё же декоративный, поднимите
`scale` в texts/, чтобы ему хватило пикселей, и посмотрите результат в игре, прежде
чем оставлять его.

Отчёт компиляции скажет, сколько символов взято из файла, и перечислит те, которые
шрифт нарисовать не смог.
