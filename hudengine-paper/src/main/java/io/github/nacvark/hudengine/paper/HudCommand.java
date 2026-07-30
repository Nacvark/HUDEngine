package io.github.nacvark.hudengine.paper;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * {@code /hudengine} — toggle, show, hide, reset, list, reload.
 *
 * Every subcommand carries its own permission so a server can hand out pieces of it. Acting on
 * someone else's HUD is a separate permission again, because "let players hide their own HUD" and
 * "let players hide anyone's HUD" are very different grants.
 *
 *   /hudengine toggle [player]
 *   /hudengine show &lt;hud&gt; [player]
 *   /hudengine hide &lt;hud&gt; [player]
 *   /hudengine reset [player]
 *   /hudengine list [player]
 *   /hudengine reload
 */
final class HudCommand implements CommandExecutor, TabCompleter {

    private static final String PERMISSION_PREFIX = "hudengine.command.";
    private static final String PERMISSION_OTHERS = "hudengine.command.others";

    private static final List<String> SUBCOMMANDS =
            List.of("toggle", "show", "hide", "reset", "list", "reload");

    /** Subcommands that name a HUD before the optional player. */
    private static final List<String> TAKE_HUD = List.of("show", "hide");

    private final HudEnginePlugin plugin;
    private final Messages messages;

    HudCommand(HudEnginePlugin plugin, Messages messages) {
        this.plugin = plugin;
        this.messages = messages;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String sub = args.length > 0 ? args[0].toLowerCase(Locale.ROOT) : "toggle";
        if (!SUBCOMMANDS.contains(sub)) {
            sender.sendMessage(messages.prefixed("command.usage", "label", label));
            return true;
        }
        // Permission is checked before anything else, including whether the engine is running.
        // Telling someone who may not use the command what state it is in is not their business.
        if (!sender.hasPermission(PERMISSION_PREFIX + sub)) {
            sender.sendMessage(messages.prefixed("command.no-permission"));
            return true;
        }
        // Reload is the way out of a failed compile, so it has to work while nothing is running.
        if (sub.equals("reload")) {
            plugin.reload().forEach(sender::sendMessage);
            return true;
        }

        HudService service = plugin.service();
        if (service == null) {
            sender.sendMessage(messages.prefixed("command.not-running"));
            return true;
        }

        switch (sub) {
            case "toggle" -> toggle(sender, service, args, 1);
            case "reset" -> reset(sender, service, args, 1);
            case "list" -> list(sender, service, args, 1);
            case "show", "hide" -> setHud(sender, service, label, sub, args);
            default -> sender.sendMessage(messages.prefixed("command.usage", "label", label));
        }
        return true;
    }

    private void toggle(CommandSender sender, HudService service, String[] args, int playerArg) {
        Player target = resolve(sender, args, playerArg);
        if (target == null) {
            return;
        }
        boolean shown = service.toggle(target);
        report(sender, target, shown ? "command.shown" : "command.hidden",
                shown ? "command.shown-other" : "command.hidden-other");
    }

    private void reset(CommandSender sender, HudService service, String[] args, int playerArg) {
        Player target = resolve(sender, args, playerArg);
        if (target == null) {
            return;
        }
        service.resetHuds(target);
        report(sender, target, "command.reset", "command.reset-other");
    }

    private void list(CommandSender sender, HudService service, String[] args, int playerArg) {
        sender.sendMessage(messages.prefixed("command.list-compiled",
                "huds", Messages.list(service.availableHuds())));

        Player target = args.length > playerArg ? resolve(sender, args, playerArg) : asPlayer(sender);
        if (target != null) {
            sender.sendMessage(messages.prefixed("command.list-visible",
                    "player", target.getName(),
                    "huds", Messages.list(service.visibleHuds(target))));
        }
    }

    private void setHud(CommandSender sender, HudService service, String label, String sub, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(messages.prefixed("command.usage-hud", "label", label, "sub", sub));
            return;
        }
        Player target = resolve(sender, args, 2);
        if (target == null) {
            return;
        }
        boolean show = sub.equals("show");
        String hud = args[1];

        if (!(show ? service.showHud(target, hud) : service.hideHud(target, hud))) {
            sender.sendMessage(messages.prefixed("command.hud-unknown",
                    "hud", hud, "huds", Messages.list(service.availableHuds())));
            return;
        }
        report(sender, target,
                show ? "command.hud-shown" : "command.hud-hidden",
                show ? "command.hud-shown-other" : "command.hud-hidden-other",
                "hud", hud);
    }

    /**
     * Works out whose HUD to act on: the named player, or the sender when no name is given.
     *
     * Returns null after telling the sender why, so callers can simply stop.
     */
    private Player resolve(CommandSender sender, String[] args, int playerArg) {
        if (args.length <= playerArg) {
            Player self = asPlayer(sender);
            if (self == null) {
                sender.sendMessage(messages.prefixed("command.player-only"));
            }
            return self;
        }
        String name = args[playerArg];
        if (!sender.hasPermission(PERMISSION_OTHERS)) {
            sender.sendMessage(messages.prefixed("command.no-permission-others"));
            return null;
        }
        Player target = Bukkit.getPlayerExact(name);
        if (target == null) {
            sender.sendMessage(messages.prefixed("command.player-not-found", "player", name));
        }
        return target;
    }

    private static Player asPlayer(CommandSender sender) {
        return sender instanceof Player player ? player : null;
    }

    /**
     * Confirms an action, wording it for whoever is reading.
     *
     * A player who changed their own HUD sees "HUD hidden"; an admin who changed someone else's
     * sees whose it was, and the player themselves is not told at all — an admin toggling a HUD to
     * debug it should not spam the person they are debugging.
     */
    private void report(CommandSender sender, Player target, String selfKey, String otherKey,
                        Object... replacements) {
        boolean self = sender == target;
        Object[] all = new Object[replacements.length + 2];
        System.arraycopy(replacements, 0, all, 0, replacements.length);
        all[replacements.length] = "player";
        all[replacements.length + 1] = target.getName();
        sender.sendMessage(messages.prefixed(self ? selfKey : otherKey, all));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
        HudService service = plugin.service();
        if (service == null) {
            return List.of();
        }
        if (args.length == 1) {
            return filter(SUBCOMMANDS.stream()
                    .filter(sub -> sender.hasPermission(PERMISSION_PREFIX + sub))
                    .toList(), args[0]);
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        if (args.length == 2 && TAKE_HUD.contains(sub)) {
            return filter(service.availableHuds(), args[1]);
        }
        boolean playerSlot = (args.length == 2 && List.of("toggle", "reset", "list").contains(sub))
                || (args.length == 3 && TAKE_HUD.contains(sub));
        if (playerSlot && sender.hasPermission(PERMISSION_OTHERS)) {
            return filter(Bukkit.getOnlinePlayers().stream().map(Player::getName).toList(),
                    args[args.length - 1]);
        }
        return List.of();
    }

    private static List<String> filter(List<String> candidates, String prefix) {
        String lower = prefix.toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (String candidate : candidates) {
            if (candidate.toLowerCase(Locale.ROOT).startsWith(lower)) {
                out.add(candidate);
            }
        }
        return out;
    }
}
