HUDEngine starter HUD
=====================

This folder was written on first start so the plugin does something out of the box.
It is an example, not a recommendation: delete anything you do not want.

  status    the player's face, a health bar and a hunger bar, top left
  coords    coordinates and facing, bottom centre
  compass   a compass ribbon, top centre

Everything it draws comes from values the engine resolves on its own, so it works
with no other plugin installed. The text uses the vanilla font that ships inside the
plugin, so no font file is needed either.

Edit and run /hudengine reload. Positions and text update at once; new images, fonts
or moved elements need players to rejoin, because that is when a client picks up a
new pack.

To start over, delete this folder and restart. To keep it from coming back, set
starter-hud: false in config.yml.


HUDEngine, стартовый HUD
========================

Эта папка записана при первом запуске, чтобы плагин что-то делал сразу. Это пример,
а не рекомендация: удаляйте всё, что не нужно.

  status    лицо игрока, полоса здоровья и полоса голода, слева вверху
  coords    координаты и направление, внизу по центру
  compass   лента компаса, вверху по центру

Всё, что он рисует, движок берёт из своих значений, поэтому HUD работает без единого
стороннего плагина. Текст использует ванильный шрифт, лежащий внутри плагина, так что
файл шрифта тоже не нужен.

Правьте и выполняйте /hudengine reload. Позиции и текст обновятся сразу; новые
картинки, шрифты и перемещённые элементы требуют перезахода игроков - именно тогда
клиент получает новый пак.

Чтобы начать заново, удалите эту папку и перезапустите сервер. Чтобы она больше не
появлялась, поставьте starter-hud: false в config.yml.
