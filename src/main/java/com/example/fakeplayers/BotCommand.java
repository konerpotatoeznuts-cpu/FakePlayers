package com.example.fakeplayers;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Mannequin;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

public class BotCommand implements CommandExecutor, TabCompleter {

    private static final List<String> SUBS = List.of(
            "help", "spawn", "remove", "list", "config", "pvp", "inv", "platform", "preset", "kit", "target",
            "select", "tp", "goto", "follow", "look", "say", "equip", "place", "break", "attack", "queue",
            "loop", "set", "addline", "clearlines", "use", "respawn", "reload");

    private static final List<String> SET_KEYS = List.of(
            "skin", "invulnerable", "immovable", "followop", "autochat", "chatinterval", "speed");

    private static final List<String> PVP_SUBS = List.of("on", "off", "gui", "set", "get", "reset", "list");

    private final FakePlayersPlugin plugin;

    public BotCommand(FakePlayersPlugin plugin) {
        this.plugin = plugin;
    }

    private BotManager mgr() {
        return plugin.manager();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.isOp() && !sender.hasPermission("fakeplayers.admin")) {
            return true;
        }
        if (args.length == 0 || args[0].equalsIgnoreCase("help")) {
            help(sender);
            return true;
        }
        String sub = args[0].toLowerCase(Locale.ROOT);

        switch (sub) {
            case "reload" -> {
                plugin.reloadConfig();
                PvpOptions.writeDefaults(plugin.getConfig());
                plugin.msg(sender, "reloaded");
                return true;
            }
            case "list" -> {
                if (mgr().all().isEmpty()) {
                    sender.sendMessage("No bots.");
                }
                for (Bot b : mgr().all()) {
                    Mannequin m = mgr().entity(b);
                    String where = m == null ? "not loaded"
                            : m.getWorld().getName() + " " + m.getLocation().getBlockX() + " "
                            + m.getLocation().getBlockY() + " " + m.getLocation().getBlockZ();
                    sender.sendMessage(b.name + " [" + b.platform + ", " + b.preset + (b.combat ? ", combat" : "")
                            + "] - " + where);
                }
                return true;
            }
            case "spawn" -> {
                if (!(sender instanceof Player p)) {
                    plugin.msg(sender, "players-only");
                    return true;
                }
                if (args.length < 2) {
                    plugin.msg(sender, "bad-args", "usage", "/bot spawn <name> [skin]");
                    return true;
                }
                if (mgr().get(args[1]) != null) {
                    plugin.msg(sender, "exists", "name", args[1]);
                    return true;
                }
                mgr().spawn(args[1], p.getLocation(), args.length > 2 ? args[2] : null);
                plugin.msg(sender, "spawned", "name", args[1]);
                return true;
            }
            case "remove" -> {
                if (args.length < 2) {
                    plugin.msg(sender, "bad-args", "usage", "/bot remove <name|all>");
                    return true;
                }
                if (args[1].equalsIgnoreCase("all")) {
                    for (Bot b : new ArrayList<>(mgr().all())) {
                        mgr().remove(b);
                    }
                    plugin.msg(sender, "removed", "name", "all");
                    return true;
                }
                Bot b = mgr().get(args[1]);
                if (b == null) {
                    plugin.msg(sender, "no-bot", "name", args[1]);
                    return true;
                }
                mgr().remove(b);
                plugin.msg(sender, "removed", "name", b.name);
                return true;
            }
            default -> {
                // all remaining subcommands need a bot name as first argument
            }
        }

        if (args.length < 2) {
            plugin.msg(sender, "bad-args", "usage", "/bot " + sub + " <bot> ...");
            return true;
        }
        Bot b = mgr().get(args[1]);
        if (b == null) {
            plugin.msg(sender, "no-bot", "name", args[1]);
            return true;
        }
        String[] rest = Arrays.copyOfRange(args, 2, args.length);
        Mannequin m = mgr().entity(b);

        switch (sub) {
            case "respawn" -> {
                mgr().respawn(b);
                plugin.msg(sender, "done");
                return true;
            }
            case "addline" -> {
                b.chatLines.add(String.join(" ", rest));
                mgr().save();
                plugin.msg(sender, "done");
                return true;
            }
            case "clearlines" -> {
                b.chatLines.clear();
                mgr().save();
                plugin.msg(sender, "done");
                return true;
            }
            case "loop" -> {
                b.loop = rest.length > 0 && rest[0].equalsIgnoreCase("on");
                mgr().save();
                plugin.msg(sender, "done");
                return true;
            }
            case "queue" -> {
                if (rest.length == 0) {
                    plugin.msg(sender, "bad-args", "usage",
                            "/bot queue <bot> <goto x y z|wait ticks|say text|look yaw pitch|look player|select 1-9|place|break|use|clear>");
                    return true;
                }
                if (rest[0].equalsIgnoreCase("clear")) {
                    b.queue.clear();
                    b.walkTarget = null;
                    b.waitTicks = 0;
                } else {
                    b.queue.add(String.join(" ", rest));
                }
                mgr().save();
                plugin.msg(sender, "done");
                return true;
            }
            case "set" -> {
                return handleSet(sender, b, m, rest);
            }
            case "config" -> {
                if (!(sender instanceof Player p)) {
                    plugin.msg(sender, "players-only");
                    return true;
                }
                p.openInventory(new BotGui(b).getInventory());
                return true;
            }
            case "inv" -> {
                if (!(sender instanceof Player p)) {
                    plugin.msg(sender, "players-only");
                    return true;
                }
                p.openInventory(new InvGui(b).getInventory());
                return true;
            }
            case "pvp" -> {
                return handlePvp(sender, b, rest);
            }
            case "platform" -> {
                if (rest.length < 1 || !(rest[0].equalsIgnoreCase("java") || rest[0].equalsIgnoreCase("bedrock"))) {
                    plugin.msg(sender, "bad-args", "usage", "/bot platform <bot> <java|bedrock>");
                    return true;
                }
                b.platform = Bot.Platform.valueOf(rest[0].toUpperCase(Locale.ROOT));
                if (m != null) {
                    mgr().apply(b, m);
                }
                mgr().save();
                plugin.msg(sender, "done");
                return true;
            }
            case "preset" -> {
                if (rest.length < 1 || !plugin.presets().contains(rest[0])) {
                    plugin.info(sender, "<red>Presets: <white>" + String.join(", ", plugin.presets()));
                    return true;
                }
                b.preset = rest[0];
                b.pvp.clear();
                mgr().save();
                plugin.msg(sender, "done");
                return true;
            }
            case "kit" -> {
                String kit = rest.length > 0 ? rest[0] : b.preset;
                String err = Kits.apply(plugin, b, kit);
                if (err != null) {
                    plugin.info(sender, "<red>" + err);
                    return true;
                }
                mgr().save();
                plugin.msg(sender, "done");
                return true;
            }
            case "target" -> {
                if (rest.length < 1) {
                    plugin.msg(sender, "bad-args", "usage", "/bot target <bot> <player|off>");
                    return true;
                }
                b.forcedTarget = rest[0].equalsIgnoreCase("off") ? null : rest[0];
                b.st.targetId = null;
                if (b.forcedTarget != null) {
                    b.combat = true;
                }
                mgr().save();
                plugin.msg(sender, "done");
                return true;
            }
            case "select" -> {
                try {
                    b.selected = Math.max(0, Math.min(8, Integer.parseInt(rest[0]) - 1));
                    b.equipDirty = true;
                } catch (RuntimeException ex) {
                    plugin.msg(sender, "bad-args", "usage", "/bot select <bot> <1-9>");
                    return true;
                }
                mgr().save();
                plugin.msg(sender, "done");
                return true;
            }
            case "attack" -> {
                String mode = rest.length > 0 ? rest[0].toLowerCase(Locale.ROOT) : "";
                switch (mode) {
                    case "players" -> {
                        b.combat = true;
                        b.pvp.put("target.players", "true");
                        b.pvp.put("target.hostiles", "false");
                    }
                    case "hostiles" -> {
                        b.combat = true;
                        b.pvp.put("target.players", "false");
                        b.pvp.put("target.hostiles", "true");
                    }
                    case "both" -> {
                        b.combat = true;
                        b.pvp.put("target.players", "true");
                        b.pvp.put("target.hostiles", "true");
                    }
                    case "off" -> b.combat = false;
                    default -> {
                        plugin.msg(sender, "bad-args", "usage", "/bot attack <bot> <players|hostiles|both|off>");
                        return true;
                    }
                }
                b.resetCombat();
                mgr().save();
                plugin.msg(sender, "done");
                return true;
            }
            default -> {
                // needs a loaded entity, handled below
            }
        }

        if (m == null) {
            plugin.msg(sender, "not-loaded", "name", b.name);
            return true;
        }

        switch (sub) {
            case "tp" -> {
                if (rest.length >= 3) {
                    try {
                        m.teleport(new Location(m.getWorld(), Double.parseDouble(rest[0]),
                                Double.parseDouble(rest[1]), Double.parseDouble(rest[2])));
                    } catch (NumberFormatException ex) {
                        plugin.msg(sender, "bad-args", "usage", "/bot tp <bot> [x y z]");
                        return true;
                    }
                } else if (sender instanceof Player p) {
                    m.teleport(p.getLocation());
                } else {
                    plugin.msg(sender, "players-only");
                    return true;
                }
            }
            case "goto" -> {
                if (rest.length < 3) {
                    plugin.msg(sender, "bad-args", "usage", "/bot goto <bot> <x> <y> <z>");
                    return true;
                }
                b.queue.clear();
                b.waitTicks = 0;
                mgr().runAction(b, m, "goto " + String.join(" ", rest));
            }
            case "follow" -> {
                if (rest.length < 1) {
                    plugin.msg(sender, "bad-args", "usage", "/bot follow <bot> <player|off>");
                    return true;
                }
                b.followTarget = rest[0].equalsIgnoreCase("off") ? null : rest[0];
            }
            case "look" -> {
                if (rest.length < 1) {
                    plugin.msg(sender, "bad-args", "usage", "/bot look <bot> <player|yaw pitch>");
                    return true;
                }
                mgr().runAction(b, m, "look " + String.join(" ", rest));
            }
            case "say" -> {
                if (rest.length < 1) {
                    plugin.msg(sender, "bad-args", "usage", "/bot say <bot> <message>");
                    return true;
                }
                mgr().say(b, String.join(" ", rest));
            }
            case "equip" -> {
                if (!(sender instanceof Player p)) {
                    plugin.msg(sender, "players-only");
                    return true;
                }
                ItemStack held = p.getInventory().getItemInMainHand().clone();
                String slot = rest.length > 0 ? rest[0].toLowerCase(Locale.ROOT) : "mainhand";
                ItemStack put = Bot.empty(held) ? null : held;
                switch (slot) {
                    case "mainhand" -> b.inv[b.selected] = put;
                    case "offhand" -> b.offhand = put;
                    case "head" -> b.armor[0] = put;
                    case "chest" -> b.armor[1] = put;
                    case "legs" -> b.armor[2] = put;
                    case "feet" -> b.armor[3] = put;
                    default -> {
                        plugin.msg(sender, "bad-args", "usage",
                                "/bot equip <bot> [mainhand|offhand|head|chest|legs|feet]");
                        return true;
                    }
                }
                b.equipDirty = true;
            }
            case "place" -> mgr().place(b, m);
            case "break" -> mgr().breakBlock(b, m);
            case "use" -> mgr().runAction(b, m, "use");
            default -> {
                plugin.msg(sender, "unknown-sub");
                return true;
            }
        }
        mgr().save();
        plugin.msg(sender, "done");
        return true;
    }

    private boolean handleSet(CommandSender sender, Bot b, Mannequin m, String[] rest) {
        if (rest.length < 2) {
            plugin.msg(sender, "bad-args", "usage", "/bot set <bot> <" + String.join("|", SET_KEYS) + "> <value>");
            return true;
        }
        String key = rest[0].toLowerCase(Locale.ROOT);
        String val = rest[1];
        try {
            switch (key) {
                case "skin" -> {
                    b.skin = val;
                    if (m != null) {
                        mgr().apply(b, m);
                    }
                }
                case "invulnerable" -> b.invulnerable = Boolean.parseBoolean(val);
                case "immovable" -> {
                    b.immovable = Boolean.parseBoolean(val);
                    if (m != null) {
                        m.setImmovable(b.immovable);
                    }
                }
                case "followop" -> b.followNearestOp = Boolean.parseBoolean(val);
                case "autochat" -> b.autoChat = Boolean.parseBoolean(val);
                case "chatinterval" -> b.chatIntervalSeconds = Math.max(1, Integer.parseInt(val));
                case "speed" -> b.speed = Double.parseDouble(val);
                default -> {
                    plugin.msg(sender, "bad-args", "usage", "/bot set <bot> <" + String.join("|", SET_KEYS) + "> <value>");
                    return true;
                }
            }
        } catch (NumberFormatException ex) {
            plugin.msg(sender, "bad-args", "usage", "/bot set <bot> " + key + " <number>");
            return true;
        }
        mgr().save();
        plugin.msg(sender, "done");
        return true;
    }

    private boolean handlePvp(CommandSender sender, Bot b, String[] rest) {
        String act = rest.length > 0 ? rest[0].toLowerCase(Locale.ROOT) : "gui";
        switch (act) {
            case "on", "off" -> {
                b.combat = act.equals("on");
                b.resetCombat();
            }
            case "gui" -> {
                if (!(sender instanceof Player p)) {
                    plugin.msg(sender, "players-only");
                    return true;
                }
                p.openInventory(new PvpGui(plugin, b, 0).getInventory());
                return true;
            }
            case "list" -> {
                for (PvpOptions.Opt o : PvpOptions.ALL) {
                    sender.sendMessage(o.path() + " = " + PvpOptions.raw(plugin, b, o.path())
                            + (b.pvp.containsKey(o.path()) ? " (custom)" : "") + "  - " + o.desc());
                }
                return true;
            }
            case "get" -> {
                if (rest.length < 2 || PvpOptions.get(rest[1]) == null) {
                    plugin.msg(sender, "bad-args", "usage", "/bot pvp <bot> get <option>");
                    return true;
                }
                sender.sendMessage(rest[1] + " = " + PvpOptions.raw(plugin, b, rest[1]));
                return true;
            }
            case "set" -> {
                if (rest.length < 3) {
                    plugin.msg(sender, "bad-args", "usage", "/bot pvp <bot> set <option> <value>");
                    return true;
                }
                PvpOptions.Opt o = PvpOptions.get(rest[1]);
                if (o == null) {
                    plugin.info(sender, "<red>Unknown option. Use <white>/bot pvp " + b.name + " list</white>.");
                    return true;
                }
                String value = String.join(" ", Arrays.copyOfRange(rest, 2, rest.length));
                String norm = PvpOptions.validate(o, value);
                if (norm == null) {
                    plugin.info(sender, "<red>Invalid value for " + o.path() + " (" + o.type() + ").");
                    return true;
                }
                b.pvp.put(o.path(), norm);
            }
            case "reset" -> {
                if (rest.length >= 2) {
                    b.pvp.remove(rest[1]);
                } else {
                    b.pvp.clear();
                }
            }
            default -> {
                plugin.msg(sender, "bad-args", "usage", "/bot pvp <bot> <" + String.join("|", PVP_SUBS) + ">");
                return true;
            }
        }
        mgr().save();
        plugin.msg(sender, "done");
        return true;
    }

    private void help(CommandSender s) {
        String[] lines = {
                "/bot spawn <name> [skin], /bot remove <name|all>, /bot list, /bot respawn <bot>",
                "/bot config <bot> - menu (or right-click a bot in creative)",
                "/bot inv <bot> - inventory, hotbar, armor, off hand",
                "/bot platform <bot> <java|bedrock>",
                "/bot pvp <bot> <on|off|gui|list|get|set|reset> - PvP options",
                "/bot preset <bot> <name>, /bot kit <bot> [name], /bot target <bot> <player|off>",
                "/bot attack <bot> <players|hostiles|both|off>",
                "/bot select <bot> <1-9> - choose the held hotbar slot",
                "/bot tp|goto|follow|look|say|equip|place|break|use ...",
                "/bot queue <bot> <action...> | clear, /bot loop <bot> <on|off>",
                "/bot set <bot> <key> <value>, /bot addline|clearlines <bot> [text], /bot reload"
        };
        for (String l : lines) {
            s.sendMessage(l);
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 1) {
            filter(SUBS, args[0], out);
        } else if (args.length == 2) {
            String sub = args[0].toLowerCase(Locale.ROOT);
            if (!sub.equals("spawn") && !sub.equals("list") && !sub.equals("reload") && !sub.equals("help")) {
                List<String> names = new ArrayList<>();
                for (Bot b : mgr().all()) {
                    names.add(b.name);
                }
                if (sub.equals("remove")) {
                    names.add("all");
                }
                filter(names, args[1], out);
            }
        } else if (args.length == 3) {
            String sub = args[0].toLowerCase(Locale.ROOT);
            switch (sub) {
                case "set" -> filter(SET_KEYS, args[2], out);
                case "pvp" -> filter(PVP_SUBS, args[2], out);
                case "platform" -> filter(List.of("java", "bedrock"), args[2], out);
                case "preset", "kit" -> filter(plugin.presets(), args[2], out);
                case "attack" -> filter(List.of("players", "hostiles", "both", "off"), args[2], out);
                case "loop" -> filter(List.of("on", "off"), args[2], out);
                case "equip" -> filter(List.of("mainhand", "offhand", "head", "chest", "legs", "feet"), args[2], out);
                case "queue" -> filter(List.of("goto", "wait", "say", "look", "select", "place", "break", "use", "clear"),
                        args[2], out);
                case "follow", "look", "target" -> {
                    List<String> names = new ArrayList<>();
                    for (Player p : Bukkit.getOnlinePlayers()) {
                        names.add(p.getName());
                    }
                    if (!sub.equals("look")) {
                        names.add("off");
                    }
                    filter(names, args[2], out);
                }
                default -> {
                }
            }
        } else if (args.length == 4 && args[0].equalsIgnoreCase("pvp")) {
            String act = args[2].toLowerCase(Locale.ROOT);
            if (act.equals("set") || act.equals("get") || act.equals("reset")) {
                List<String> paths = new ArrayList<>();
                for (PvpOptions.Opt o : PvpOptions.ALL) {
                    paths.add(o.path());
                }
                filter(paths, args[3], out);
            }
        } else if (args.length == 5 && args[0].equalsIgnoreCase("pvp") && args[2].equalsIgnoreCase("set")) {
            PvpOptions.Opt o = PvpOptions.get(args[3]);
            if (o != null && o.type() == PvpOptions.Type.BOOL) {
                filter(List.of("true", "false"), args[4], out);
            } else if (o != null && o.type() == PvpOptions.Type.CHOICE) {
                filter(Arrays.asList(o.choices()), args[4], out);
            }
        }
        return out;
    }

    private static void filter(List<String> options, String prefix, List<String> out) {
        for (String o : options) {
            if (o.toLowerCase(Locale.ROOT).startsWith(prefix.toLowerCase(Locale.ROOT))) {
                out.add(o);
            }
        }
    }
}
