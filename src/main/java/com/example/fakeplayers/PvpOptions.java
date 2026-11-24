package com.example.fakeplayers;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Registry of every PvP option. Resolution order for a value:
 * per-bot override, then the bot's preset (config pvp.presets.NAME), then config pvp.defaults, then the built-in default.
 */
public final class PvpOptions {

    public enum Type { BOOL, INT, DOUBLE, CHOICE, TEXT }

    public record Opt(String path, Type type, String def, double step, String desc, String[] choices, Material icon) {
    }

    public static final List<Opt> ALL = new ArrayList<>();
    private static final Map<String, Opt> BY_PATH = new HashMap<>();

    private PvpOptions() {
    }

    private static void add(Opt o) {
        ALL.add(o);
        BY_PATH.put(o.path(), o);
    }

    private static void b(String path, boolean def, Material icon, String desc) {
        add(new Opt(path, Type.BOOL, String.valueOf(def), 0, desc, null, icon));
    }

    private static void i(String path, int def, double step, Material icon, String desc) {
        add(new Opt(path, Type.INT, String.valueOf(def), step, desc, null, icon));
    }

    private static void d(String path, double def, double step, Material icon, String desc) {
        add(new Opt(path, Type.DOUBLE, String.valueOf(def), step, desc, null, icon));
    }

    private static void c(String path, String def, Material icon, String desc, String... choices) {
        add(new Opt(path, Type.CHOICE, def, 0, desc, choices, icon));
    }

    private static void t(String path, String def, Material icon, String desc) {
        add(new Opt(path, Type.TEXT, def, 0, desc, null, icon));
    }

    static {
        // ---- aim
        d("aim.speed", 22, 4, Material.SPYGLASS, "Max degrees the bot can turn per tick");
        d("aim.error", 1.5, 0.5, Material.TARGET, "Random aim wobble in degrees");
        i("aim.reaction-ticks", 4, 1, Material.CLOCK, "Ticks the bot waits after spotting a target");
        // ---- movement
        d("move.speed", 0.23, 0.01, Material.LEATHER_BOOTS, "Walk speed (blocks per tick)");
        d("move.sprint-speed", 0.28, 0.01, Material.GOLDEN_BOOTS, "Sprint speed (blocks per tick)");
        c("travel.mode", "walk", Material.LEATHER_BOOTS, "How the bot travels (goto, follow, linked clicks)", "walk", "run", "sprintjump");
        d("travel.run-multiplier", 1.3, 0.05, Material.SUGAR, "Speed multiplier for run and sprintjump");
        b("nav.build", true, Material.COBBLESTONE, "Linked travel: build up with blocks to reach higher targets");
        c("nav.spread", "ring", Material.HEAVY_CORE, "How several bots arrange around a clicked spot", "ring", "scattered");
        d("nav.spread-radius", 1.8, 0.2, Material.LEAD, "Spacing of the ring, or size of the scatter area");
        b("nav.drops", true, Material.COBBLESTONE, "Blocks dug by linked travel drop as items");
        b("nav.stealth-click", true, Material.LEATHER_BOOTS, "Sneaking while using the link item puts bots in stealth (sneak, silent, no name tag; attacks come from behind)");
        d("nav.stealth-speed", 0.35, 0.05, Material.SOUL_SAND, "Speed multiplier while in stealth");
        b("nav.stealth-spot-drop", true, Material.ENDER_EYE, "Stealth ends for all bots when the target looks at one of them");
        d("nav.stealth-spot-angle", 55.0, 5, Material.SPYGLASS, "How far from the target's crosshair a bot can be and still be spotted (degrees)");
        b("nav.dig", true, Material.IRON_PICKAXE, "Linked travel: dig down or through walls to reach targets");
        c("move.strafe", "circle", Material.COMPASS, "Strafing style", "none", "circle", "random");
        i("move.strafe-interval", 45, 5, Material.REPEATER, "Ticks between strafe direction decisions");
        d("move.min-distance", 2.0, 0.25, Material.IRON_BARS, "Back off when closer than this");
        d("move.max-distance", 2.8, 0.25, Material.LEAD, "Close in when farther than this");
        b("move.jump-crit", true, Material.SLIME_BALL, "Jump before hitting to land critical hits");
        d("move.jump-chance", 0.3, 0.1, Material.RABBIT_FOOT, "Chance to jump for a crit on each attack");
        b("move.retreat", false, Material.FEATHER, "Run away when health is low");
        d("move.retreat-health", 6, 1, Material.REDSTONE, "Health (points) below which the bot retreats");
        // ---- hit
        d("hit.reach", 3.0, 0.1, Material.STICK, "Melee reach in blocks");
        d("hit.cps", 10, 1, Material.REDSTONE_TORCH, "Clicks per second (Bedrock bots)");
        d("hit.miss-chance", 0.05, 0.05, Material.COBBLESTONE, "Chance an attack swings and misses");
        b("hit.crit", true, Material.GOLDEN_SWORD, "Allow critical hits while falling");
        d("hit.knockback-extra", 0.0, 0.1, Material.PISTON, "Extra knockback added to every hit");
        i("hit.cooldown-ticks", -1, 1, Material.CLOCK, "Java attack cooldown in ticks (-1 = from weapon)");
        // ---- targeting
        c("target.mode", "nearest", Material.ENDER_EYE, "Who to pick first", "nearest", "lowest-health");
        b("target.players", true, Material.PLAYER_HEAD, "Attack players");
        b("target.hostiles", false, Material.ZOMBIE_HEAD, "Attack hostile mobs");
        b("target.bots", false, Material.ARMOR_STAND, "Attack other bots");
        b("target.ops", false, Material.COMMAND_BLOCK, "Attack OPs");
        d("target.range", 24, 4, Material.BOW, "Distance at which targets are noticed");
        d("target.lose-range", 40, 4, Material.COBWEB, "Distance at which a target is dropped");
        t("target.ignore", "", Material.BARRIER, "Comma separated player or bot names to never attack");
        // ---- weapons
        t("weapon.prefer", "MACE,AXE,SWORD,TRIDENT", Material.NETHERITE_SWORD, "Weapon order: SWORD, AXE, MACE, TRIDENT, SPEAR");
        b("weapon.shield-break", true, Material.IRON_AXE, "Switch to an axe against blocking players");
        // ---- shield
        b("shield.enabled", true, Material.SHIELD, "Block with a shield if one is held");
        d("shield.block-chance", 0.6, 0.1, Material.OAK_PLANKS, "Chance to raise the shield each decision");
        i("shield.hold-ticks", 20, 2, Material.CLOCK, "Ticks the shield stays up");
        i("shield.lower-ticks", 8, 1, Material.CLOCK, "Ticks the shield stays down (attack window)");
        d("shield.range", 4.5, 0.5, Material.SPYGLASS, "Raise the shield when the target is this close");
        i("shield.axe-disable-ticks", 100, 10, Material.DIAMOND_AXE, "How long an axe hit disables a shield");
        // ---- survival
        b("totem.refill", true, Material.TOTEM_OF_UNDYING, "Keep a totem in the off hand");
        i("totem.delay", 12, 0, Material.CLOCK, "Ticks the bot takes to put a new totem on (like opening the inventory)");
        i("totem.delay-random", 6, 0, Material.CLOCK, "Extra random ticks added to the totem delay");
        b("heal.enabled", true, Material.GOLDEN_APPLE, "Eat and drink when hurt");
        d("heal.gap-health", 12, 1, Material.GOLDEN_APPLE, "Eat golden apples at or below this health");
        d("heal.egap-health", 7, 1, Material.ENCHANTED_GOLDEN_APPLE, "Eat enchanted apples at or below this health");
        d("heal.potion-health", 10, 1, Material.POTION, "Use healing potions at or below this health");
        i("heal.eat-ticks", 32, 2, Material.CLOCK, "Ticks it takes to eat or drink");
        b("heal.splash", true, Material.SPLASH_POTION, "Throw splash healing potions at own feet");
        b("buff.enabled", true, Material.BREWING_STAND, "Drink speed, strength and similar potions when fighting");
        i("buff.cooldown", 200, 20, Material.CLOCK, "Ticks between buff checks");
        b("water.mlg", true, Material.WATER_BUCKET, "Water bucket clutch when falling");
        // ---- items
        b("items.consume", true, Material.CHEST, "Use up items (false = infinite supplies)");
        // ---- cobweb
        b("web.enabled", false, Material.COBWEB, "Place cobwebs on the target");
        d("web.range", 4.0, 0.5, Material.COBWEB, "Max distance to place a web");
        i("web.cooldown", 60, 10, Material.CLOCK, "Ticks between webs");
        i("web.cleanup-ticks", 200, 20, Material.SHEARS, "Remove placed webs after this many ticks (0 = never)");
        // ---- lava
        b("lava.enabled", false, Material.LAVA_BUCKET, "Place lava on the target");
        d("lava.range", 4.0, 0.5, Material.LAVA_BUCKET, "Max distance to place lava");
        i("lava.cooldown", 100, 10, Material.CLOCK, "Ticks between lava placements");
        b("lava.require-web", true, Material.COBWEB, "Only use lava on webbed targets");
        i("lava.pickup-ticks", 6, 1, Material.BUCKET, "Ticks before the lava is picked back up (0 = never)");
        i("water.pickup-ticks", 5, 1, Material.WATER_BUCKET, "Ticks before placed water is picked back up (0 = never)");
        b("lava.retrieve", true, Material.BUCKET, "Get the lava bucket back after cleanup");
        // ---- drain
        b("drain.enabled", false, Material.BUCKET, "Drain water the target places to escape webs");
        i("drain.reaction-ticks", 5, 1, Material.CLOCK, "Ticks before the bot reacts to placed water");
        d("drain.range", 8.0, 1, Material.SPYGLASS, "Max distance to drain from");
        b("drain.lava", true, Material.LAVA_BUCKET, "Also drain lava the target places");
        b("drain.require-bucket", true, Material.BUCKET, "Needs an empty bucket to drain");
        b("drain.reweb", true, Material.COBWEB, "Place a new web after draining");
        // ---- crystals
        b("crystal.enabled", false, Material.END_CRYSTAL, "Crystal PvP");
        d("crystal.range", 6.0, 0.5, Material.END_CRYSTAL, "Max distance to the target for crystals");
        b("crystal.place-obsidian", true, Material.OBSIDIAN, "Place obsidian bases if none exist");
        i("crystal.hit-delay", 2, 1, Material.CLOCK, "Ticks between placing and detonating");
        d("crystal.min-target-damage", 8, 1, Material.DIAMOND_SWORD, "Only detonate if estimated target damage is at least this");
        d("crystal.max-self-damage", 6, 1, Material.SHIELD, "Never detonate if estimated self damage exceeds this");
        i("crystal.cooldown", 6, 1, Material.CLOCK, "Ticks between crystal placements");
        i("crystal.max-active", 2, 1, Material.END_CRYSTAL, "Max crystals at once");
        b("crystal.block-damage", false, Material.TNT, "Let crystal explosions destroy blocks");
        i("crystal.obsidian-cleanup-ticks", 200, 20, Material.SHEARS, "Remove placed obsidian after this many ticks (0 = never)");
        // ---- anchors
        b("anchor.enabled", false, Material.RESPAWN_ANCHOR, "Anchor PvP");
        d("anchor.range", 6.0, 0.5, Material.RESPAWN_ANCHOR, "Max distance to the target for anchors");
        i("anchor.explode-delay", 3, 1, Material.CLOCK, "Ticks between placing and detonating");
        i("anchor.cooldown", 40, 5, Material.CLOCK, "Ticks between anchors");
        d("anchor.min-target-damage", 8, 1, Material.DIAMOND_SWORD, "Only detonate if estimated target damage is at least this");
        d("anchor.max-self-damage", 6, 1, Material.SHIELD, "Never detonate if estimated self damage exceeds this");
        b("anchor.block-damage", false, Material.TNT, "Let anchor explosions destroy blocks");
        // ---- mace
        b("mace.enabled", false, Material.MACE, "Mace smash attacks");
        b("mace.wind-charge", true, Material.WIND_CHARGE, "Launch with a wind charge (otherwise just hops)");
        d("mace.launch-range", 12.0, 1, Material.SPYGLASS, "Launch when the target is within this distance");
        i("mace.cooldown", 60, 10, Material.CLOCK, "Ticks between launches");
        b("mace.dive", true, Material.ANVIL, "Dive straight down onto the target");
        // ---- ranged
        b("ranged.enabled", false, Material.BOW, "Use bows, crossbows or thrown tridents at range");
        d("ranged.min-range", 8.0, 1, Material.BOW, "Only shoot when farther than this");
        d("ranged.max-range", 32.0, 2, Material.SPYGLASS, "Only shoot when closer than this");
        i("ranged.draw-ticks", 20, 1, Material.CLOCK, "Ticks to draw a bow");
        d("ranged.spread", 1.5, 0.5, Material.TARGET, "Shot inaccuracy in degrees");
        b("ranged.infinite-ammo", true, Material.ARROW, "Bows do not need arrows");
        b("ranged.trident-throw", false, Material.TRIDENT, "Throw tridents at range");
        // ---- pearls
        b("pearl.enabled", false, Material.ENDER_PEARL, "Use ender pearls");
        d("pearl.min-distance", 14.0, 1, Material.ENDER_PEARL, "Pearl toward targets farther than this");
        i("pearl.cooldown", 100, 10, Material.CLOCK, "Ticks between pearls");
        b("pearl.escape", true, Material.ENDER_PEARL, "Pearl away when low on health");
        d("pearl.escape-health", 6, 1, Material.REDSTONE, "Escape pearl at or below this health");
        b("pearl.damage", true, Material.REDSTONE, "Pearl landings hurt the bot (5 damage like a real player)");
        b("pcatch.enabled", false, Material.ENDER_EYE, "Pearl catch: pearl + wind charge to land above the target, then mace dive");
        c("pcatch.mode", "auto", Material.ENDER_PEARL, "Pearl direction: up, horizontal, down or auto", "auto", "up", "horizontal", "down");
        d("pcatch.min-range", 6.0, 1, Material.SPYGLASS, "Minimum distance to the target for a pearl catch");
        d("pcatch.max-range", 30.0, 1, Material.SPYGLASS, "Maximum distance to the target for a pearl catch");
        i("pcatch.wind-delay", 3, 0, Material.WIND_CHARGE, "Ticks between the pearl and the wind charge");
        d("pcatch.height", 8.0, 1, Material.FEATHER, "How far above the landing spot the bot ends up");
        i("pcatch.cooldown", 160, 20, Material.CLOCK, "Ticks between pearl catches");
        b("pcrit.enabled", false, Material.DIAMOND_SWORD, "Punish crit: when you hit the bot and it is knocked up, it crits back while falling");
        i("pcrit.window", 14, 4, Material.CLOCK, "Ticks after being hit in which the punish crit can land");
        // ---- swapping tricks
        b("swap.attribute", true, Material.NETHERITE_AXE, "Attribute swap: charge with the fastest weapon, hit with the best one");
        b("swap.breach", true, Material.MACE, "Breach swap: use a Breach mace for the smash if you have one");
        // ---- spear + mace
        b("spearmace.enabled", false, Material.TRIDENT, "Spear lunge, then swap to the mace and smash");
        d("spearmace.min-range", 4.0, 0.5, Material.SPYGLASS, "Minimum distance to lunge");
        d("spearmace.max-range", 10.0, 0.5, Material.SPYGLASS, "Maximum distance to lunge");
        i("spearmace.cooldown", 80, 10, Material.CLOCK, "Ticks between lunges");
        // ---- elytra mace / dive bomb
        b("elytra.enabled", false, Material.ELYTRA, "Elytra macing: fly up with rockets, then dive bomb the target");
        d("elytra.height", 14.0, 1, Material.FIREWORK_ROCKET, "Height above the target before diving");
        i("elytra.cooldown", 200, 20, Material.CLOCK, "Ticks between flights");
        b("elytra.swap-chest", true, Material.NETHERITE_CHESTPLATE, "Swap the chestplate for an elytra and back");
        // ---- stun slam
        b("stunslam.enabled", false, Material.NETHERITE_AXE, "After an axe hit breaks a shield, launch and mace smash straight away");
        // ---- grappling
        b("grapple.enabled", false, Material.ENDER_PEARL, "Grapple: pearl to a target that is mid-air, teleport to them, then mace them");
        d("grapple.range", 24.0, 1, Material.SPYGLASS, "Max distance for a grapple pearl");
        i("grapple.cooldown", 100, 10, Material.CLOCK, "Ticks between grapples");
        d("grapple.min-height", 2.5, 0.5, Material.FEATHER, "Target must be this high above the ground");
        // ---- cart pvp
        b("cart.enabled", false, Material.TNT_MINECART, "Cart PvP: rail + TNT minecart on the target");
        c("cart.mode", "insta", Material.TNT_MINECART, "insta = blows up at once, xbow = a crossbow shot sets it off", "insta", "xbow");
        d("cart.range", 6.0, 0.5, Material.RAIL, "Max distance to the target for carts");
        i("cart.cooldown", 80, 10, Material.CLOCK, "Ticks between carts");
        d("cart.min-target-damage", 8, 1, Material.DIAMOND_SWORD, "Only blow up if estimated target damage is at least this");
        d("cart.max-self-damage", 8, 1, Material.SHIELD, "Never blow up if estimated self damage exceeds this");
        b("cart.block-damage", false, Material.TNT, "Let cart explosions destroy blocks");
        // ---- bridging
        b("bridge.enabled", true, Material.COBBLESTONE, "Build toward the target with blocks when there are no pearls");
        b("bridge.pillar", true, Material.OAK_PLANKS, "Pillar up to targets above the bot");
        i("bridge.cleanup-ticks", 400, 20, Material.SHEARS, "Remove bridge blocks after this many ticks (0 = never)");
        // ---- misc
        b("shield.visual", true, Material.SHIELD, "Show the shield raised while blocking");
        d("move.strafe-amount", 0.15, 0.05, Material.COMPASS, "How much sideways movement (0 = none, 1 = lots)");
        i("lava.combo-delay", 2, 1, Material.LAVA_BUCKET, "Ticks between the web and the lava on top of it");
        b("water.web-escape", true, Material.WATER_BUCKET, "Place water on a web the bot is stuck in");
        i("web.cut-ticks", 30, 5, Material.SHEARS, "Cut free of a web after this many ticks if no water (0 = never)");
        b("pearl.heal", true, Material.GOLDEN_APPLE, "Pearl away before healing when an enemy is close");
    }

    public static Opt get(String path) {
        return BY_PATH.get(path);
    }

    /** Adds every option to pvp.defaults in the config so people can edit them there. */
    public static void writeDefaults(FileConfiguration cfg) {
        for (Opt o : ALL) {
            Object v = switch (o.type()) {
                case BOOL -> Boolean.parseBoolean(o.def());
                case INT -> (int) Double.parseDouble(o.def());
                case DOUBLE -> Double.parseDouble(o.def());
                default -> o.def();
            };
            cfg.addDefault("pvp.defaults." + o.path(), v);
        }
        cfg.options().copyDefaults(true);
    }

    // ------------------------------------------------------------ reading

    public static String raw(FakePlayersPlugin plugin, Bot bot, String path) {
        String v = bot.pvp.get(path);
        if (v != null) {
            return v;
        }
        FileConfiguration cfg = plugin.getConfig();
        Object o = cfg.get("pvp.presets." + bot.preset + "." + path);
        if (o != null && !(o instanceof ConfigurationSection)) {
            return String.valueOf(o);
        }
        o = cfg.get("pvp.defaults." + path);
        if (o != null && !(o instanceof ConfigurationSection)) {
            return String.valueOf(o);
        }
        Opt opt = BY_PATH.get(path);
        return opt == null ? null : opt.def();
    }

    public static boolean bool(FakePlayersPlugin plugin, Bot bot, String path) {
        return Boolean.parseBoolean(raw(plugin, bot, path));
    }

    public static double dbl(FakePlayersPlugin plugin, Bot bot, String path) {
        try {
            return Double.parseDouble(raw(plugin, bot, path));
        } catch (RuntimeException ex) {
            Opt o = BY_PATH.get(path);
            return o == null ? 0 : Double.parseDouble(o.def());
        }
    }

    public static int integer(FakePlayersPlugin plugin, Bot bot, String path) {
        return (int) Math.round(dbl(plugin, bot, path));
    }

    public static String str(FakePlayersPlugin plugin, Bot bot, String path) {
        String s = raw(plugin, bot, path);
        return s == null ? "" : s;
    }

    // ------------------------------------------------------------ writing

    /** Returns the normalised value, or null if the text is not valid for this option. */
    public static String validate(Opt o, String value) {
        try {
            switch (o.type()) {
                case BOOL -> {
                    String v = value.toLowerCase(Locale.ROOT);
                    if (v.equals("true") || v.equals("on") || v.equals("yes")) {
                        return "true";
                    }
                    if (v.equals("false") || v.equals("off") || v.equals("no")) {
                        return "false";
                    }
                    return null;
                }
                case INT -> {
                    return String.valueOf((int) Math.round(Double.parseDouble(value)));
                }
                case DOUBLE -> {
                    return String.valueOf(Double.parseDouble(value));
                }
                case CHOICE -> {
                    for (String ch : o.choices()) {
                        if (ch.equalsIgnoreCase(value)) {
                            return ch;
                        }
                    }
                    return null;
                }
                default -> {
                    return value;
                }
            }
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    /** Next value for GUI clicks, or null for free-text options. */
    public static String step(Opt o, String cur, int dir, int mult) {
        switch (o.type()) {
            case BOOL -> {
                return String.valueOf(!Boolean.parseBoolean(cur));
            }
            case INT -> {
                double v = Double.parseDouble(cur) + dir * o.step() * mult;
                double min = Double.parseDouble(o.def()) < 0 ? -1 : 0;
                return String.valueOf((int) Math.round(Math.max(min, v)));
            }
            case DOUBLE -> {
                double v = Double.parseDouble(cur) + dir * o.step() * mult;
                v = Math.max(0, Math.round(v * 100.0) / 100.0);
                return String.valueOf(v);
            }
            case CHOICE -> {
                int idx = 0;
                for (int k = 0; k < o.choices().length; k++) {
                    if (o.choices()[k].equalsIgnoreCase(cur)) {
                        idx = k;
                    }
                }
                int n = o.choices().length;
                return o.choices()[((idx + dir) % n + n) % n];
            }
            default -> {
                return null;
            }
        }
    }
}
