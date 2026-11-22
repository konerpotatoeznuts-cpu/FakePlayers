package com.example.fakeplayers;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Mannequin;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

public class BotCommand implements CommandExecutor, TabCompleter {

    private static final List<String> SUBS = List.of(
            "help", "spawn", "remove", "list", "config", "tp", "goto", "follow", "look", "say",
            "equip", "inv", "place", "break", "attack", "queue", "loop", "set", "addline", "clearlines",
            "use", "respawn", "reload");

    private static final List<String> SET_KEYS = List.of(
            "skin", "invulnerable", "immovable", "attackplayers", "attackhostiles", "followop",
            "autochat", "chatinterval", "damage", "speed");

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
                    sender.sendMessage(b.name + " - " + where);
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

        if (sub.equals("respawn")) {
            mgr().respawn(b);
            plugin.msg(sender, "done");
            return true;
        }
        if (sub.equals("addline")) {
            b.chatLines.add(String.join(" ", rest));
            mgr().save();
            plugin.msg(sender, "done");
            return true;
        }
        if (sub.equals("clearlines")) {
            b.chatLines.clear();
            mgr().save();
            plugin.msg(sender, "done");
            return true;
        }
        if (sub.equals("loop")) {
            b.loop = rest.length > 0 && rest[0].equalsIgnoreCase("on");
            mgr().save();
            plugin.msg(sender, "done");
            return true;
        }
        if (sub.equals("queue") && rest.length > 0 && rest[0].equalsIgnoreCase("clear")) {
            b.queue.clear();
            b.walkTarget = null;
            b.waitTicks = 0;
            mgr().save();
            plugin.msg(sender, "done");
            return true;
        }
        if (sub.equals("queue")) {
            if (rest.length == 0) {
                plugin.msg(sender, "bad-args", "usage",
                        "/bot queue <bot> <goto x y z|wait ticks|say text|look yaw pitch|look player|place|break|use|clear>");
                return true;
            }
            b.queue.add(String.join(" ", rest));
            mgr().save();
            plugin.msg(sender, "done");
            return true;
        }
        if (sub.equals("set")) {
            return handleSet(sender, b, rest);
        }
        if (sub.equals("config")) {
            if (!(sender instanceof Player p)) {
                plugin.msg(sender, "players-only");
                return true;
            }
            p.openInventory(new BotGui(b).getInventory());
            return true;
        }

        Mannequin m = mgr().entity(b);
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
                EntityEquipment eq = m.getEquipment();
                String slot = rest.length > 0 ? rest[0].toLowerCase(Locale.ROOT) : "mainhand";
                switch (slot) {
                    case "mainhand" -> eq.setItemInMainHand(held);
                    case "offhand" -> eq.setItemInOffHand(held);
                    case "head" -> eq.setHelmet(held);
                    case "chest" -> eq.setChestplate(held);
                    case "legs" -> eq.setLeggings(held);
                    case "feet" -> eq.setBoots(held);
                    default -> {
                        plugin.msg(sender, "bad-args", "usage",
                                "/bot equip <bot> [mainhand|offhand|head|chest|legs|feet]");
                        return true;
                    }
                }
            }
            case "inv" -> {
                if (!(sender instanceof Player p)) {
                    plugin.msg(sender, "players-only");
                    return true;
                }
                p.openInventory(new EquipGui(b, m).getInventory());
                return true;
            }
            case "place" -> mgr().place(b, m);
            case "break" -> mgr().breakBlock(b, m);
            case "use" -> mgr().runAction(b, m, "use");
            case "attack" -> {
                String mode = rest.length > 0 ? rest[0].toLowerCase(Locale.ROOT) : "";
                switch (mode) {
                    case "players" -> {
                        b.attackPlayers = true;
                        b.attackHostiles = false;
                    }
                    case "hostiles" -> {
                        b.attackPlayers = false;
                        b.attackHostiles = true;
                    }
                    case "both" -> {
                        b.attackPlayers = true;
                        b.attackHostiles = true;
                    }
                    case "off" -> {
                        b.attackPlayers = false;
                        b.attackHostiles = false;
                    }
                    default -> {
                        plugin.msg(sender, "bad-args", "usage", "/bot attack <bot> <players|hostiles|both|off>");
                        return true;
                    }
                }
            }
            default -> {
                plugin.msg(sender, "unknown-sub");
                return true;
            }
        }
        mgr().save();
        plugin.msg(sender, "done");
        return true;
    }

    private boolean handleSet(CommandSender sender, Bot b, String[] rest) {
        if (rest.length < 2) {
            plugin.msg(sender, "bad-args", "usage", "/bot set <bot> <" + String.join("|", SET_KEYS) + "> <value>");
            return true;
        }
        String key = rest[0].toLowerCase(Locale.ROOT);
        String val = rest[1];
        Mannequin m = mgr().entity(b);
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
                case "attackplayers" -> b.attackPlayers = Boolean.parseBoolean(val);
                case "attackhostiles" -> b.attackHostiles = Boolean.parseBoolean(val);
                case "followop" -> b.followNearestOp = Boolean.parseBoolean(val);
                case "autochat" -> b.autoChat = Boolean.parseBoolean(val);
                case "chatinterval" -> b.chatIntervalSeconds = Math.max(1, Integer.parseInt(val));
                case "damage" -> b.damage = Double.parseDouble(val);
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

    private void help(CommandSender s) {
        String[] lines = {
                "/bot spawn <name> [skin] - spawn a bot where you stand",
                "/bot remove <name|all>, /bot list, /bot respawn <bot>",
                "/bot config <bot> - open the config GUI (or right-click a bot in creative)",
                "/bot tp <bot> [x y z], /bot goto <bot> x y z, /bot follow <bot> <player|off>",
                "/bot look <bot> <player|yaw pitch>, /bot say <bot> <msg>",
                "/bot inv <bot> - equipment screen: drag items from your inventory into the bot's slots",
                "/bot equip <bot> [slot] - copies the item in your hand onto the bot",
                "/bot place|break <bot> - build where the bot is looking",
                "/bot attack <bot> <players|hostiles|both|off>",
                "/bot queue <bot> <action...> | clear, /bot loop <bot> <on|off>",
                "/bot set <bot> <key> <value>, /bot addline|clearlines <bot> [text]",
                "/bot use <bot> - run the on-use integration commands, /bot reload"
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
                case "attack" -> filter(List.of("players", "hostiles", "both", "off"), args[2], out);
                case "loop" -> filter(List.of("on", "off"), args[2], out);
                case "equip" -> filter(List.of("mainhand", "offhand", "head", "chest", "legs", "feet"), args[2], out);
                case "queue" -> filter(List.of("goto", "wait", "say", "look", "place", "break", "use", "clear"), args[2], out);
                case "follow", "look" -> {
                    List<String> names = new ArrayList<>();
                    for (Player p : Bukkit.getOnlinePlayers()) {
                        names.add(p.getName());
                    }
                    if (sub.equals("follow")) {
                        names.add("off");
                    }
                    filter(names, args[2], out);
                }
                default -> {
                }
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
