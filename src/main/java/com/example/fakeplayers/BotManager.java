package com.example.fakeplayers;

import io.papermc.paper.datacomponent.item.ResolvableProfile;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Mannequin;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.metadata.FixedMetadataValue;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

public class BotManager {

    /** A block the bot placed that should be removed later. */
    public record Placed(Location loc, Material expect, long expire, Material refundFrom, Material refundTo, Bot bot) {
    }

    private final FakePlayersPlugin plugin;
    private final Map<String, Bot> bots = new LinkedHashMap<>();
    private final NamespacedKey botKey;
    private final File file;
    private final List<Placed> placed = new ArrayList<>();
    private final BotCombat combat;
    private final BotWeapons weapons;
    private final BotNav nav;
    private final BotTab tab;
    private long tickCount;

    public BotManager(FakePlayersPlugin plugin) {
        this.plugin = plugin;
        this.botKey = new NamespacedKey(plugin, "bot_name");
        this.file = new File(plugin.getDataFolder(), "bots.yml");
        this.combat = new BotCombat(plugin, this);
        this.weapons = new BotWeapons(plugin, this);
        this.nav = new BotNav(plugin, this);
        this.tab = new BotTab(plugin);
    }

    public BotTab tab() {
        return tab;
    }

    /** Broadcasts a configurable message. Placeholders: bot, killer, victim. An empty message is skipped. */
    public void announce(String key, Bot b, String killer, String victim) {
        String def = switch (key) {
            case "bot-join" -> "<yellow><bot> joined the game";
            case "bot-leave" -> "<yellow><bot> left the game";
            case "bot-kill" -> "<red><victim> was slain by <bot>";
            case "bot-death" -> "<white><bot> was slain by <killer>";
            default -> "<white><bot> died";
        };
        String fmt = plugin.getConfig().getString("messages." + key, def);
        if (fmt == null || fmt.isBlank()) {
            return;
        }
        String shown = b.displayName(plugin.getConfig().getString("platform.bedrock-prefix", "."));
        Bukkit.broadcast(plugin.parse(fmt,
                net.kyori.adventure.text.minimessage.tag.resolver.Placeholder.unparsed("bot", shown),
                net.kyori.adventure.text.minimessage.tag.resolver.Placeholder.unparsed("killer", killer == null ? "" : killer),
                net.kyori.adventure.text.minimessage.tag.resolver.Placeholder.unparsed("victim", victim == null ? "" : victim)));
    }

    public BotNav nav() {
        return nav;
    }

    public BotWeapons weapons() {
        return weapons;
    }

    public BotCombat combat() {
        return combat;
    }

    public long tickCount() {
        return tickCount;
    }

    // ------------------------------------------------------------ lookup

    private static String k(String name) {
        return name.toLowerCase(Locale.ROOT);
    }

    public Bot get(String name) {
        return bots.get(k(name));
    }

    public Collection<Bot> all() {
        return bots.values();
    }

    public Mannequin entity(Bot b) {
        if (b.entityId == null) {
            return null;
        }
        Entity e = Bukkit.getEntity(b.entityId);
        if (e instanceof Mannequin m && m.isValid()) {
            return m;
        }
        return null;
    }

    public Bot botOf(Entity e) {
        if (!(e instanceof Mannequin)) {
            return null;
        }
        String n = e.getPersistentDataContainer().get(botKey, PersistentDataType.STRING);
        return n == null ? null : bots.get(k(n));
    }

    // ------------------------------------------------------------ lifecycle

    public Bot spawn(String name, Location loc, String skin) {
        Bot b = new Bot(name);
        b.skin = (skin == null || skin.isEmpty()) ? name : skin;
        b.home = loc.clone();
        b.speed = plugin.getConfig().getDouble("defaults.speed", 0.2);
        b.chatIntervalSeconds = plugin.getConfig().getInt("defaults.chat-interval-seconds", 60);
        b.preset = plugin.getConfig().getString("defaults.preset", "sword");
        bots.put(k(name), b);
        spawnEntity(b, loc);
        announce("bot-join", b, null, null);
        save();
        return b;
    }

    public void spawnEntity(Bot b, Location loc) {
        Mannequin m = loc.getWorld().spawn(loc, Mannequin.class, e ->
                e.getPersistentDataContainer().set(botKey, PersistentDataType.STRING, b.name));
        b.entityId = m.getUniqueId();
        b.resetCombat();
        b.stealth = false;
        b.stealthOn = false;
        b.equipDirty = true;
        apply(b, m);
        tab.showAll(b);
    }

    public void respawn(Bot b) {
        Mannequin old = entity(b);
        if (old != null) {
            old.remove();
        }
        if (b.home != null && b.home.getWorld() != null) {
            spawnEntity(b, b.home);
        }
    }

    public void respawnLater(Bot b, long delayTicks) {
        respawnLater(b, delayTicks, false);
    }

    public void respawnLater(Bot b, long delayTicks, boolean announceJoin) {
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (bots.containsValue(b) && b.home != null && b.home.getWorld() != null) {
                if (plugin.getConfig().getBoolean("death.restock", true)
                        && b.dropItems && b.lastKit != null) {
                    Kits.apply(plugin, b, b.lastKit);
                }
                spawnEntity(b, b.home);
                if (announceJoin) {
                    announce("bot-join", b, null, null);
                }
            }
        }, delayTicks);
    }

    public void remove(Bot b) {
        Mannequin m = entity(b);
        if (m != null) {
            m.remove();
        }
        tab.hideAll(b);
        bots.remove(k(b.name));
        save();
    }

    /** Pushes the bot's saved settings onto its entity. */
    public void apply(Bot b, Mannequin m) {
        String prefix = plugin.getConfig().getString("platform.bedrock-prefix", ".");
        m.customName(Component.text(b.displayName(prefix)));
        m.setCustomNameVisible(true);
        m.setDescription(null);
        m.setImmovable(b.immovable);
        m.setPersistent(true);
        m.setRemoveWhenFarAway(false);
        m.getPersistentDataContainer().set(new NamespacedKey(plugin, "bot_platform"),
                PersistentDataType.STRING, b.platform.name());
        m.setMetadata("fakeplayers_platform", new FixedMetadataValue(plugin, b.platform.name()));
        if (b.skin != null && b.skin.matches("[A-Za-z0-9_]{1,16}")) {
            m.setProfile(ResolvableProfile.resolvableProfile().name(b.skin).build());
        }
        syncEquipment(b, m);
    }

    private static ItemStack nz(ItemStack i) {
        return Bot.empty(i) ? new ItemStack(Material.AIR) : i.clone();
    }

    /** Shows the bot's armor, selected hotbar item and off hand on the entity. */
    public void syncEquipment(Bot b, Mannequin m) {
        EntityEquipment eq = m.getEquipment();
        eq.setItem(EquipmentSlot.HEAD, nz(b.armor[0]));
        eq.setItem(EquipmentSlot.CHEST, nz(b.armor[1]));
        eq.setItem(EquipmentSlot.LEGS, nz(b.armor[2]));
        eq.setItem(EquipmentSlot.FEET, nz(b.armor[3]));
        eq.setItem(EquipmentSlot.HAND, nz(b.inv[b.selected]));
        eq.setItem(EquipmentSlot.OFF_HAND, nz(b.offhand));
        pushEquipment(b, m);
    }

    /** Forces the equipment packet to nearby players so armor and held items always render. */
    public void pushEquipment(Bot b, Mannequin m) {
        java.util.Map<EquipmentSlot, ItemStack> map = new java.util.EnumMap<>(EquipmentSlot.class);
        map.put(EquipmentSlot.HEAD, nz(b.armor[0]));
        map.put(EquipmentSlot.CHEST, nz(b.armor[1]));
        map.put(EquipmentSlot.LEGS, nz(b.armor[2]));
        map.put(EquipmentSlot.FEET, nz(b.armor[3]));
        map.put(EquipmentSlot.HAND, nz(b.inv[b.selected]));
        map.put(EquipmentSlot.OFF_HAND, nz(b.offhand));
        for (org.bukkit.entity.Player p : m.getWorld().getPlayers()) {
            if (p.getLocation().distanceSquared(m.getLocation()) < 96 * 96) {
                p.sendEquipmentChange(m, map);
            }
        }
    }

    /** Reads hands back from the entity (after a totem pop the game shrinks the entity's copy). */
    public void pullHands(Bot b, Mannequin m) {
        EntityEquipment eq = m.getEquipment();
        ItemStack main = eq.getItemInMainHand();
        ItemStack off = eq.getItemInOffHand();
        b.inv[b.selected] = Bot.empty(main) ? null : main.clone();
        b.offhand = Bot.empty(off) ? null : off.clone();
    }

    // ------------------------------------------------------------ persistence

    public void save() {
        YamlConfiguration y = new YamlConfiguration();
        for (Bot b : bots.values()) {
            String p = "bots." + k(b.name) + ".";
            y.set(p + "name", b.name);
            y.set(p + "entity", b.entityId == null ? null : b.entityId.toString());
            y.set(p + "skin", b.skin);
            y.set(p + "home", b.home);
            y.set(p + "immovable", b.immovable);
            y.set(p + "invulnerable", b.invulnerable);
            y.set(p + "drop-items", b.dropItems);
            y.set(p + "banner-base", b.bannerBase);
            y.set(p + "banner-layers", b.bannerLayers);
            y.set(p + "trims", java.util.Arrays.asList(b.trims));
            y.set(p + "leave-on-death", b.leaveOnDeath);
            y.set(p + "patrol", b.patrol);
            y.set(p + "greet", b.greet);
            List<String> rl = new ArrayList<>();
            for (Map.Entry<String, String> re : b.replies.entrySet()) {
                rl.add(re.getKey() + "|" + re.getValue());
            }
            y.set(p + "replies", rl);
            y.set(p + "rejoin-seconds", b.rejoinSeconds);
            y.set(p + "combat", b.combat);
            y.set(p + "follow-nearest-op", b.followNearestOp);
            y.set(p + "follow-target", b.followTarget);
            y.set(p + "forced-target", b.forcedTarget);
            y.set(p + "auto-chat", b.autoChat);
            y.set(p + "loop", b.loop);
            y.set(p + "speed", b.speed);
            y.set(p + "chat-interval", b.chatIntervalSeconds);
            y.set(p + "chat-lines", b.chatLines);
            y.set(p + "queue", new ArrayList<>(b.queue));
            y.set(p + "platform", b.platform.name());
            y.set(p + "preset", b.preset);
            y.set(p + "last-kit", b.lastKit);
            y.set(p + "selected", b.selected);
            for (Map.Entry<String, String> en : b.pvp.entrySet()) {
                y.set(p + "pvp." + en.getKey().replace('.', '_'), en.getKey() + "=" + en.getValue());
            }
            for (int i = 0; i < 36; i++) {
                if (!Bot.empty(b.inv[i])) {
                    y.set(p + "inv." + i, b.inv[i]);
                }
            }
            for (int i = 0; i < 4; i++) {
                if (!Bot.empty(b.armor[i])) {
                    y.set(p + "armor." + i, b.armor[i]);
                }
            }
            if (!Bot.empty(b.offhand)) {
                y.set(p + "offhand", b.offhand);
            }
        }
        try {
            plugin.getDataFolder().mkdirs();
            y.save(file);
        } catch (IOException ex) {
            plugin.getLogger().warning("Could not save bots.yml: " + ex.getMessage());
        }
    }

    public void load() {
        bots.clear();
        if (!file.exists()) {
            return;
        }
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection root = y.getConfigurationSection("bots");
        if (root == null) {
            return;
        }
        for (String key : root.getKeys(false)) {
            ConfigurationSection s = root.getConfigurationSection(key);
            if (s == null) {
                continue;
            }
            Bot b = new Bot(s.getString("name", key));
            String id = s.getString("entity");
            if (id != null) {
                try {
                    b.entityId = UUID.fromString(id);
                } catch (IllegalArgumentException ignored) {
                    // leave null
                }
            }
            b.skin = s.getString("skin", b.name);
            b.home = s.getLocation("home");
            b.immovable = s.getBoolean("immovable");
            b.invulnerable = s.getBoolean("invulnerable");
            b.dropItems = s.getBoolean("drop-items", true);
            b.bannerBase = s.getString("banner-base");
            b.bannerLayers = new ArrayList<>(s.getStringList("banner-layers"));
            List<String> tl = s.getStringList("trims");
            for (int ti = 0; ti < 4 && ti < tl.size(); ti++) {
                b.trims[ti] = tl.get(ti) == null || tl.get(ti).isEmpty() ? null : tl.get(ti);
            }
            b.leaveOnDeath = s.getBoolean("leave-on-death", false);
            b.patrol = new ArrayList<>(s.getStringList("patrol"));
            b.greet = s.getString("greet");
            for (String rl : s.getStringList("replies")) {
                int bar = rl.indexOf('|');
                if (bar > 0) {
                    b.replies.put(rl.substring(0, bar), rl.substring(bar + 1));
                }
            }
            b.rejoinSeconds = s.getInt("rejoin-seconds", 10);
            b.combat = s.getBoolean("combat");
            b.followNearestOp = s.getBoolean("follow-nearest-op");
            b.followTarget = s.getString("follow-target");
            b.forcedTarget = s.getString("forced-target");
            b.autoChat = s.getBoolean("auto-chat");
            b.loop = s.getBoolean("loop");
            b.speed = s.getDouble("speed", 0.2);
            b.chatIntervalSeconds = s.getInt("chat-interval", 60);
            b.chatLines = new ArrayList<>(s.getStringList("chat-lines"));
            b.queue.addAll(s.getStringList("queue"));
            try {
                b.platform = Bot.Platform.valueOf(s.getString("platform", "JAVA"));
            } catch (IllegalArgumentException ex) {
                b.platform = Bot.Platform.JAVA;
            }
            b.preset = s.getString("preset", "sword");
            b.lastKit = s.getString("last-kit");
            b.selected = Math.max(0, Math.min(8, s.getInt("selected", 0)));
            ConfigurationSection pv = s.getConfigurationSection("pvp");
            if (pv != null) {
                for (String pk : pv.getKeys(false)) {
                    String line = pv.getString(pk, "");
                    int eq = line.indexOf('=');
                    if (eq > 0) {
                        b.pvp.put(line.substring(0, eq), line.substring(eq + 1));
                    }
                }
            }
            ConfigurationSection inv = s.getConfigurationSection("inv");
            if (inv != null) {
                for (String ik : inv.getKeys(false)) {
                    try {
                        int idx = Integer.parseInt(ik);
                        if (idx >= 0 && idx < 36) {
                            b.inv[idx] = inv.getItemStack(ik);
                        }
                    } catch (NumberFormatException ignored) {
                        // skip
                    }
                }
            }
            ConfigurationSection ar = s.getConfigurationSection("armor");
            if (ar != null) {
                for (String ak : ar.getKeys(false)) {
                    try {
                        int idx = Integer.parseInt(ak);
                        if (idx >= 0 && idx < 4) {
                            b.armor[idx] = ar.getItemStack(ak);
                        }
                    } catch (NumberFormatException ignored) {
                        // skip
                    }
                }
            }
            b.offhand = s.getItemStack("offhand");
            b.equipDirty = true;
            bots.put(k(b.name), b);
        }
    }

    // ------------------------------------------------------------ ticking

    public void tick() {
        tickCount++;
        tickPlaced();
        for (Bot b : new ArrayList<>(bots.values())) {
            Mannequin m = entity(b);
            if (m == null || m.isDead()) {
                continue;
            }
            try {
                if (b.equipDirty || tickCount % 20 == 0) {
                    syncEquipment(b, m);
                    b.equipDirty = false;
                }
                tickBot(b, m);
            } catch (Exception ex) {
                plugin.getLogger().warning("Error ticking bot " + b.name + ": " + ex);
            }
        }
    }

    private void tickBot(Bot b, Mannequin m) {
        // auto chat
        if (b.autoChat && !b.chatLines.isEmpty()) {
            if (--b.chatCountdown <= 0) {
                b.chatCountdown = Math.max(1, b.chatIntervalSeconds) * 20;
                say(b, b.chatLines.get(ThreadLocalRandom.current().nextInt(b.chatLines.size())));
            }
        }

        greetTick(b, m);
        pickupTick(b, m);
        nav.checkAttack(b, m);
        if (b.clickTarget != null && nav.stealthTick(b, m)) {
            return;
        }

        // combat has priority
        if (b.combat && combat.tick(b, m)) {
            return;
        }

        // travelling to a block the player clicked with a linked item
        if (nav.tick(b, m)) {
            return;
        }

        // continuous look
        if (b.lookAt != null) {
            Player lp = Bukkit.getPlayerExact(b.lookAt);
            if (lp != null && lp.getWorld().equals(m.getWorld())) {
                face(m, lp.getEyeLocation());
            }
        }

        // following
        Player followed = null;
        if (b.followTarget != null) {
            followed = Bukkit.getPlayerExact(b.followTarget);
        } else if (b.followNearestOp) {
            double best = Double.MAX_VALUE;
            for (Player p : m.getWorld().getPlayers()) {
                if (!p.isOp() || p.getGameMode() == GameMode.SPECTATOR) {
                    continue;
                }
                double d = p.getLocation().distanceSquared(m.getLocation());
                if (d < best) {
                    best = d;
                    followed = p;
                }
            }
        }
        if (followed != null && followed.getWorld().equals(m.getWorld())) {
            double dist = followed.getLocation().distance(m.getLocation());
            if (dist > plugin.getConfig().getDouble("follow.distance", 3.0)) {
                walkOrArrive(b, m, followed.getLocation());
            } else {
                face(m, followed.getEyeLocation());
            }
            return;
        }

        if (seekItems(b, m)) {
            return;
        }
        processQueue(b, m);
        patrolTick(b);
    }

    // ------------------------------------------------------------ picking up items

    private void pickupTick(Bot b, Mannequin m) {
        if (tickCount % 4 != 0 || !PvpOptions.bool(plugin, b, "pickup.enabled")) {
            return;
        }
        double r = Math.max(0.5, PvpOptions.dbl(plugin, b, "pickup.range"));
        int taken = 0;
        for (org.bukkit.entity.Entity e : m.getNearbyEntities(r, r, r)) {
            if (!(e instanceof org.bukkit.entity.Item it) || it.getPickupDelay() > 0 || it.isDead()
                    || m.getUniqueId().equals(it.getThrower())) {
                continue;
            }
            ItemStack stack = it.getItemStack();
            if (!b.canFit(stack)) {
                continue;
            }
            ItemStack rest = b.give(stack.clone());
            if (rest == null) {
                it.remove();
            } else {
                it.setItemStack(rest);
            }
            m.getWorld().playSound(m.getLocation(), org.bukkit.Sound.ENTITY_ITEM_PICKUP, 0.5f, 1.2f);
            if (++taken >= 3) {
                break;
            }
        }
    }

    /** Idle bots walk to dropped items nearby (only when they have room for them). */
    private boolean seekItems(Bot b, Mannequin m) {
        if (!PvpOptions.bool(plugin, b, "pickup.enabled") || !PvpOptions.bool(plugin, b, "pickup.seek")
                || b.immovable || b.walkTarget != null || b.navGoal != null || !b.queue.isEmpty()
                || !b.patrol.isEmpty() || b.waitTicks > 0) {
            return false;
        }
        if (b.seekPause > 0) {
            b.seekPause--;
            return false;
        }
        double range = PvpOptions.dbl(plugin, b, "pickup.seek-range");
        org.bukkit.entity.Item best = null;
        double bestD = Double.MAX_VALUE;
        for (org.bukkit.entity.Entity e : m.getNearbyEntities(range, range, range)) {
            if (e instanceof org.bukkit.entity.Item it && !it.isDead() && !m.getUniqueId().equals(it.getThrower())
                    && b.canFit(it.getItemStack()) && it.getPickupDelay() < 60) {
                double d = it.getLocation().distanceSquared(m.getLocation());
                if (d < bestD) {
                    bestD = d;
                    best = it;
                }
            }
        }
        if (best == null) {
            b.seekTicks = 0;
            return false;
        }
        if (++b.seekTicks > 240) {
            b.seekTicks = 0;
            b.seekPause = 400;
            return false;
        }
        walkOrArrive(b, m, best.getLocation());
        return true;
    }

    // ------------------------------------------------------------ patrol, greetings, replies

    private void patrolTick(Bot b) {
        if (b.patrol.isEmpty() || b.queue.size() > 0 || b.waitTicks > 0 || b.navGoal != null) {
            return;
        }
        if (b.walkTarget != null) {
            return;
        }
        if (b.patrolActive) {
            b.patrolActive = false;
            b.waitTicks = Math.max(0, plugin.getConfig().getInt("patrol.pause-ticks", 60));
            return;
        }
        Location wp = plugin.scripts().get(b.patrol.get(b.patrolIdx % b.patrol.size()));
        b.patrolIdx++;
        if (wp != null) {
            b.walkTarget = wp;
            b.walkTicks = 0;
            b.patrolActive = true;
        }
    }

    private void greetTick(Bot b, Mannequin m) {
        if (b.greet == null || tickCount % 10 != 0) {
            return;
        }
        double range = plugin.getConfig().getDouble("npc.greet-range", 6.0);
        long cd = plugin.getConfig().getLong("npc.greet-cooldown-seconds", 300) * 20L;
        for (Player p : m.getWorld().getPlayers()) {
            if (p.getGameMode() == GameMode.SPECTATOR
                    || p.getLocation().distanceSquared(m.getLocation()) > range * range) {
                continue;
            }
            Long last = b.greeted.get(p.getUniqueId());
            if (last != null && tickCount - last < cd) {
                continue;
            }
            b.greeted.put(p.getUniqueId(), tickCount);
            face(m, p.getEyeLocation());
            say(b, b.greet.replace("%player%", p.getName()));
            return;
        }
    }

    /** Bots answer chat keywords from nearby players. */
    public void npcReply(Player p, String text) {
        String low = text.toLowerCase(Locale.ROOT);
        double range = plugin.getConfig().getDouble("npc.reply-range", 24.0);
        int delay = plugin.getConfig().getInt("npc.reply-delay-ticks", 30);
        for (Bot b : all()) {
            Mannequin m = entity(b);
            if (b.replies.isEmpty() || m == null || !m.getWorld().equals(p.getWorld())
                    || m.getLocation().distance(p.getLocation()) > range || tickCount - b.lastReply < 40) {
                continue;
            }
            for (Map.Entry<String, String> en : b.replies.entrySet()) {
                if (low.contains(en.getKey())) {
                    b.lastReply = tickCount;
                    String resp = en.getValue().replace("%player%", p.getName());
                    long wait = delay + ThreadLocalRandom.current().nextInt(20);
                    Bukkit.getScheduler().runTaskLater(plugin, () -> {
                        Mannequin mm = entity(b);
                        if (mm != null) {
                            face(mm, p.getEyeLocation());
                            say(b, resp);
                        }
                    }, wait);
                    break;
                }
            }
        }
    }

    // ------------------------------------------------------------ placed blocks

    /**
     * Places a block the bot is allowed to place and optionally removes it later.
     * When refundTo is set, removing it gives the bot refundTo in place of one refundFrom (e.g. BUCKET to LAVA_BUCKET).
     */
    public boolean placeTemp(Bot b, Block block, Material mat, int cleanupTicks, Material refundFrom, Material refundTo) {
        if (!plugin.getConfig().getBoolean("build.enabled", true) || blocked(mat)) {
            return false;
        }
        if (!(block.isEmpty() || block.isReplaceable() || block.getType() == Material.COBWEB)) {
            return false;
        }
        if (!supported(block)) {
            return false; // a player can only place against an existing block
        }
        block.setType(mat, true);
        if (cleanupTicks > 0) {
            placed.add(new Placed(block.getLocation(), mat, tickCount + cleanupTicks, refundFrom, refundTo, b));
        }
        return true;
    }

    /** True when at least one neighbouring block is solid ground, a web or placed fluid to click against. */
    public static boolean supported(Block block) {
        for (org.bukkit.block.BlockFace f : new org.bukkit.block.BlockFace[]{org.bukkit.block.BlockFace.DOWN,
                org.bukkit.block.BlockFace.NORTH, org.bukkit.block.BlockFace.SOUTH, org.bukkit.block.BlockFace.EAST,
                org.bukkit.block.BlockFace.WEST, org.bukkit.block.BlockFace.UP}) {
            Block n = block.getRelative(f);
            Material t = n.getType();
            if (!n.isEmpty() && !n.isLiquid() && t != Material.FIRE && t != Material.SOUL_FIRE && !n.isReplaceable()
                    || t == Material.COBWEB) {
                return true;
            }
        }
        return false;
    }

    private void tickPlaced() {
        if (placed.isEmpty()) {
            return;
        }
        Iterator<Placed> it = placed.iterator();
        while (it.hasNext()) {
            Placed p = it.next();
            if (tickCount < p.expire()) {
                continue;
            }
            it.remove();
            removePlaced(p, true);
        }
    }

    private void removePlaced(Placed p, boolean refund) {
        if (p.loc().getWorld() == null) {
            return;
        }
        Block block = p.loc().getBlock();
        if (block.getType() == p.expect()) {
            block.setType(Material.AIR);
        }
        if (refund && p.refundTo() != null && p.refundFrom() != null && p.bot() != null) {
            int slot = p.bot().find(p.refundFrom());
            if (slot >= 0) {
                p.bot().convertOne(slot, p.refundTo());
            }
        }
    }

    /** Removes everything the bots placed (called when the plugin disables). */
    public void cleanupPlaced() {
        for (Placed p : placed) {
            removePlaced(p, false);
        }
        placed.clear();
    }

    // ------------------------------------------------------------ movement & looking (queue / follow)

    public boolean moveToward(Bot b, Mannequin m, Location target) {
        if (b.immovable) {
            return false;
        }
        Location loc = m.getLocation();
        double dx = target.getX() - loc.getX();
        double dz = target.getZ() - loc.getZ();
        double h = Math.hypot(dx, dz);
        if (h < 0.5) {
            return true;
        }
        double nx = dx / h;
        double nz = dz / h;
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        m.setRotation(yaw, 0f);
        boolean sprintJump = "sprintjump".equals(PvpOptions.str(plugin, b, "travel.mode"));
        double speed = b.speed;
        if (b.stealth) {
            sprintJump = false;
            speed *= PvpOptions.dbl(plugin, b, "nav.stealth-speed");
        } else if (!"walk".equals(PvpOptions.str(plugin, b, "travel.mode"))) {
            speed *= PvpOptions.dbl(plugin, b, "travel.run-multiplier");
        }
        walk(b, m, nx, nz, speed);
        if (sprintJump && m.isOnGround()) {
            Vector v = m.getVelocity();
            m.setVelocity(new Vector(v.getX(), 0.42, v.getZ()));
        }
        return false;
    }

    /** Moves in a horizontal direction with simple step-up jumping. */
    public void walk(Bot b, Mannequin m, double nx, double nz, double speed) {
        if (b.immovable) {
            return;
        }
        Location loc = m.getLocation();
        Vector v = m.getVelocity();
        double y = v.getY();
        Location ahead = loc.clone().add(nx * 0.8, 0, nz * 0.8);
        if (m.isOnGround()
                && ahead.getBlock().getType().isSolid()
                && !ahead.clone().add(0, 1, 0).getBlock().getType().isSolid()
                && !ahead.clone().add(0, 2, 0).getBlock().getType().isSolid()) {
            y = 0.42;
        }
        if (m.isOnGround()) {
            m.setVelocity(new Vector(nx * speed, y, nz * speed));
        } else {
            // player-like air control: momentum is kept (0.91 drag), steering is a small push, never faster than ground speed
            double acc = 0.026 * PvpOptions.dbl(plugin, b, "move.air-speed") * Math.max(0.5, speed / 0.2);
            double ax = v.getX() * 0.91 + nx * acc;
            double az = v.getZ() * 0.91 + nz * acc;
            double cap = Math.max(0.1, speed * 1.05);
            double hs = Math.hypot(ax, az);
            if (hs > cap && hs > Math.hypot(v.getX(), v.getZ())) {
                ax = ax / hs * Math.max(cap, Math.hypot(v.getX(), v.getZ()));
                az = az / hs * Math.max(cap, Math.hypot(v.getX(), v.getZ()));
            }
            m.setVelocity(new Vector(ax, y, az));
        }
    }

    /** Walking used by combat: direction and speed ease in and out instead of snapping. */
    public void walkSmooth(Bot b, Mannequin m, double wantX, double wantZ, double speed, boolean moving) {
        Bot.CombatState st = b.st;
        st.dirX += (wantX - st.dirX) * 0.3;
        st.dirZ += (wantZ - st.dirZ) * 0.3;
        st.speedFactor = moving ? Math.min(1.0, st.speedFactor + 0.25) : Math.max(0.0, st.speedFactor - 0.35);
        double len = Math.hypot(st.dirX, st.dirZ);
        if (len < 1e-4 || st.speedFactor <= 0.01) {
            return;
        }
        walk(b, m, st.dirX / len, st.dirZ / len, speed * st.speedFactor * Math.min(1.0, len));
    }

    public void face(Mannequin m, Location target) {
        Location eye = m.getEyeLocation();
        double dx = target.getX() - eye.getX();
        double dy = target.getY() - eye.getY();
        double dz = target.getZ() - eye.getZ();
        double h = Math.sqrt(dx * dx + dz * dz);
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, h));
        m.setRotation(yaw, pitch);
    }

    /** Pathfinds toward a goal; falls back to walking straight when there is no walkable route. */
    private boolean walkOrArrive(Bot b, Mannequin m, Location goal) {
        BotNav.PathState s = nav.walkPath(b, m, goal);
        if (s == BotNav.PathState.FAILED) {
            return moveToward(b, m, goal);
        }
        return s == BotNav.PathState.ARRIVED;
    }

    // ------------------------------------------------------------ queue

    private void processQueue(Bot b, Mannequin m) {
        if (b.waitTicks > 0) {
            b.waitTicks--;
            return;
        }
        if (b.walkTarget != null) {
            b.walkTicks++;
            if (walkOrArrive(b, m, b.walkTarget) || b.walkTicks > 600) {
                b.walkTarget = null;
                b.walkTicks = 0;
            } else {
                return;
            }
        }
        String a = b.queue.poll();
        if (a == null) {
            return;
        }
        if (b.loop) {
            b.queue.add(a);
        }
        runAction(b, m, a);
    }

    /** One action: goto x y z | wait ticks | say text | look yaw pitch | look player | select 1-9 | place | break | use */
    public void runAction(Bot b, Mannequin m, String action) {
        String[] p = action.trim().split(" ", 2);
        String arg = p.length > 1 ? p[1] : "";
        try {
            switch (p[0].toLowerCase(Locale.ROOT)) {
                case "goto" -> {
                    String[] c = arg.trim().split("\\s+");
                    if (c.length == 1) {
                        Location wp = plugin.scripts().get(c[0]);
                        if (wp == null) {
                            plugin.getLogger().warning("Unknown waypoint: " + c[0]);
                        } else {
                            b.walkTarget = wp;
                            b.walkTicks = 0;
                        }
                    } else {
                        b.walkTarget = new Location(m.getWorld(),
                                Double.parseDouble(c[0]), Double.parseDouble(c[1]), Double.parseDouble(c[2]));
                        b.walkTicks = 0;
                    }
                }
                case "tp" -> {
                    Location wp = plugin.scripts().get(arg.trim());
                    if (wp != null) {
                        m.teleport(wp);
                    }
                }
                case "sneak" -> m.setPose(arg.trim().equalsIgnoreCase("on")
                        ? org.bukkit.entity.Pose.SNEAKING : org.bukkit.entity.Pose.STANDING, arg.trim().equalsIgnoreCase("on"));
                case "swing" -> m.swingMainHand();
                case "target" -> b.forcedTarget = arg.trim().equalsIgnoreCase("off") ? null : arg.trim();
                case "command" -> Bukkit.dispatchCommand(Bukkit.getConsoleSender(),
                        arg.replace("%bot%", b.name));
                case "wait" -> b.waitTicks = Integer.parseInt(arg.trim());
                case "say" -> say(b, arg);
                case "select" -> {
                    b.selected = Math.max(0, Math.min(8, Integer.parseInt(arg.trim()) - 1));
                    b.equipDirty = true;
                }
                case "look" -> {
                    String[] c = arg.trim().split("\\s+");
                    if (c[0].equalsIgnoreCase("off")) {
                        b.lookAt = null;
                    } else if (c.length >= 2 && c[1].equalsIgnoreCase("continuous")) {
                        b.lookAt = c[0];
                    } else if (c.length >= 2) {
                        m.setRotation(Float.parseFloat(c[0]), Float.parseFloat(c[1]));
                    } else {
                        Player t = Bukkit.getPlayerExact(c[0]);
                        if (t != null) {
                            face(m, t.getEyeLocation());
                        }
                    }
                }
                case "place" -> place(b, m);
                case "break" -> breakBlock(b, m);
                case "use" -> runCommands("integration.on-use", placeholders(b, m, "victim", ""));
                default -> plugin.getLogger().warning("Unknown bot action: " + action);
            }
        } catch (RuntimeException ex) {
            plugin.getLogger().warning("Bad bot action '" + action + "' for " + b.name + ": " + ex.getMessage());
        }
    }

    // ------------------------------------------------------------ chat & building

    public void say(Bot b, String message) {
        String fmt = plugin.getConfig().getString("chat.format", "<white>\\<<bot>> <message></white>");
        String prefix = plugin.getConfig().getString("platform.bedrock-prefix", ".");
        Bukkit.broadcast(plugin.parse(fmt,
                Placeholder.unparsed("bot", b.displayName(prefix)),
                Placeholder.unparsed("message", message)));
    }

    public boolean place(Bot b, Mannequin m) {
        if (!plugin.getConfig().getBoolean("build.enabled", true)) {
            return false;
        }
        ItemStack held = b.held();
        if (held == null || !held.getType().isBlock() || blocked(held.getType())) {
            return false;
        }
        RayTraceResult r = m.getWorld().rayTraceBlocks(m.getEyeLocation(), m.getEyeLocation().getDirection(), 4.5);
        if (r == null || r.getHitBlock() == null || r.getHitBlockFace() == null) {
            return false;
        }
        BlockFace face = r.getHitBlockFace();
        Block target = r.getHitBlock().getRelative(face);
        if (!target.isEmpty() && !target.isReplaceable()) {
            return false;
        }
        target.setType(held.getType());
        m.swingMainHand();
        if (plugin.getConfig().getBoolean("build.consume-items", false)) {
            b.take(b.selected, 1);
        }
        return true;
    }

    public boolean breakBlock(Bot b, Mannequin m) {
        if (!plugin.getConfig().getBoolean("build.enabled", true)) {
            return false;
        }
        RayTraceResult r = m.getWorld().rayTraceBlocks(m.getEyeLocation(), m.getEyeLocation().getDirection(), 4.5);
        if (r == null || r.getHitBlock() == null) {
            return false;
        }
        Block hit = r.getHitBlock();
        if (blocked(hit.getType()) || hit.getType().getHardness() < 0) {
            return false;
        }
        m.swingMainHand();
        ItemStack tool = b.held();
        return tool == null ? hit.breakNaturally() : hit.breakNaturally(tool);
    }

    public boolean blocked(Material mat) {
        for (String s : plugin.getConfig().getStringList("build.blocked-materials")) {
            if (s.equalsIgnoreCase(mat.name())) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------ integration

    public Map<String, String> placeholders(Bot b, Mannequin m, String key, String value) {
        Map<String, String> ph = new LinkedHashMap<>();
        ph.put("bot", b.name);
        ph.put(key, value);
        ItemStack held = b.held();
        ph.put("item", held == null ? "AIR" : held.getType().name());
        ph.put("platform", b.platform.name());
        if (m != null) {
            Location l = m.getLocation();
            ph.put("x", String.valueOf(l.getBlockX()));
            ph.put("y", String.valueOf(l.getBlockY()));
            ph.put("z", String.valueOf(l.getBlockZ()));
        }
        return ph;
    }

    public void runCommands(String path, Map<String, String> ph) {
        List<String> cmds = plugin.getConfig().getStringList(path);
        for (String c : cmds) {
            for (Map.Entry<String, String> en : ph.entrySet()) {
                c = c.replace("%" + en.getKey() + "%", en.getValue());
            }
            if (c.startsWith("/")) {
                c = c.substring(1);
            }
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), c);
        }
    }
}
