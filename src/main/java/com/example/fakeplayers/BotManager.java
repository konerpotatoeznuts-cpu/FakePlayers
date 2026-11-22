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
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mannequin;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

public class BotManager {

    private final FakePlayersPlugin plugin;
    private final Map<String, Bot> bots = new LinkedHashMap<>();
    private final NamespacedKey botKey;
    private final File file;

    public BotManager(FakePlayersPlugin plugin) {
        this.plugin = plugin;
        this.botKey = new NamespacedKey(plugin, "bot_name");
        this.file = new File(plugin.getDataFolder(), "bots.yml");
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
        b.damage = plugin.getConfig().getDouble("defaults.damage", 4.0);
        b.chatIntervalSeconds = plugin.getConfig().getInt("defaults.chat-interval-seconds", 60);
        bots.put(k(name), b);
        spawnEntity(b, loc);
        save();
        return b;
    }

    public void spawnEntity(Bot b, Location loc) {
        Mannequin m = loc.getWorld().spawn(loc, Mannequin.class, e ->
                e.getPersistentDataContainer().set(botKey, PersistentDataType.STRING, b.name));
        b.entityId = m.getUniqueId();
        apply(b, m);
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
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (bots.containsValue(b) && b.home != null && b.home.getWorld() != null) {
                spawnEntity(b, b.home);
            }
        }, delayTicks);
    }

    public void remove(Bot b) {
        Mannequin m = entity(b);
        if (m != null) {
            m.remove();
        }
        bots.remove(k(b.name));
        save();
    }

    /** Pushes the bot's saved settings onto its entity. */
    public void apply(Bot b, Mannequin m) {
        m.customName(Component.text(b.name));
        m.setCustomNameVisible(true);
        m.setDescription(null);
        m.setImmovable(b.immovable);
        m.setPersistent(true);
        m.setRemoveWhenFarAway(false);
        if (b.skin != null && b.skin.matches("[A-Za-z0-9_]{1,16}")) {
            m.setProfile(ResolvableProfile.resolvableProfile().name(b.skin).build());
        }
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
            y.set(p + "attack-players", b.attackPlayers);
            y.set(p + "attack-hostiles", b.attackHostiles);
            y.set(p + "follow-nearest-op", b.followNearestOp);
            y.set(p + "follow-target", b.followTarget);
            y.set(p + "auto-chat", b.autoChat);
            y.set(p + "loop", b.loop);
            y.set(p + "speed", b.speed);
            y.set(p + "damage", b.damage);
            y.set(p + "chat-interval", b.chatIntervalSeconds);
            y.set(p + "chat-lines", b.chatLines);
            y.set(p + "queue", new ArrayList<>(b.queue));
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
                    b.entityId = java.util.UUID.fromString(id);
                } catch (IllegalArgumentException ignored) {
                    // leave null
                }
            }
            b.skin = s.getString("skin", b.name);
            b.home = s.getLocation("home");
            b.immovable = s.getBoolean("immovable");
            b.invulnerable = s.getBoolean("invulnerable");
            b.attackPlayers = s.getBoolean("attack-players");
            b.attackHostiles = s.getBoolean("attack-hostiles");
            b.followNearestOp = s.getBoolean("follow-nearest-op");
            b.followTarget = s.getString("follow-target");
            b.autoChat = s.getBoolean("auto-chat");
            b.loop = s.getBoolean("loop");
            b.speed = s.getDouble("speed", 0.2);
            b.damage = s.getDouble("damage", 4.0);
            b.chatIntervalSeconds = s.getInt("chat-interval", 60);
            b.chatLines = new ArrayList<>(s.getStringList("chat-lines"));
            b.queue.addAll(s.getStringList("queue"));
            bots.put(k(b.name), b);
        }
    }

    // ------------------------------------------------------------ ticking

    public void tick() {
        for (Bot b : new ArrayList<>(bots.values())) {
            Mannequin m = entity(b);
            if (m == null || m.isDead()) {
                continue;
            }
            try {
                tickBot(b, m);
            } catch (Exception ex) {
                plugin.getLogger().warning("Error ticking bot " + b.name + ": " + ex);
            }
        }
    }

    private void tickBot(Bot b, Mannequin m) {
        if (b.attackCooldown > 0) {
            b.attackCooldown--;
        }

        // auto chat
        if (b.autoChat && !b.chatLines.isEmpty()) {
            if (--b.chatCountdown <= 0) {
                b.chatCountdown = Math.max(1, b.chatIntervalSeconds) * 20;
                say(b, b.chatLines.get(ThreadLocalRandom.current().nextInt(b.chatLines.size())));
            }
        }

        // combat has priority
        if (b.attackPlayers || b.attackHostiles) {
            LivingEntity target = findTarget(b, m);
            if (target != null) {
                fight(b, m, target);
                return;
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
                moveToward(b, m, followed.getLocation());
            } else {
                face(m, followed.getEyeLocation());
            }
            return;
        }

        processQueue(b, m);
    }

    // ------------------------------------------------------------ movement & looking

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

        Vector v = m.getVelocity();
        double y = v.getY();
        Location ahead = loc.clone().add(nx * 0.8, 0, nz * 0.8);
        if (m.isOnGround()
                && ahead.getBlock().getType().isSolid()
                && !ahead.clone().add(0, 1, 0).getBlock().getType().isSolid()
                && !ahead.clone().add(0, 2, 0).getBlock().getType().isSolid()) {
            y = 0.42;
        }
        m.setVelocity(new Vector(nx * b.speed, y, nz * b.speed));
        return false;
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

    // ------------------------------------------------------------ combat

    private LivingEntity findTarget(Bot b, Mannequin m) {
        double range = plugin.getConfig().getDouble("combat.sight-range", 12.0);
        boolean attackOps = plugin.getConfig().getBoolean("combat.attack-ops", false);
        LivingEntity best = null;
        double bestD = Double.MAX_VALUE;
        for (Entity e : m.getNearbyEntities(range, range, range)) {
            if (!(e instanceof LivingEntity le) || le.isDead() || e.equals(m)) {
                continue;
            }
            boolean ok = false;
            if (e instanceof Player p) {
                ok = b.attackPlayers
                        && (p.getGameMode() == GameMode.SURVIVAL || p.getGameMode() == GameMode.ADVENTURE)
                        && (attackOps || !p.isOp());
            } else if (e instanceof Monster) {
                ok = b.attackHostiles;
            }
            if (!ok) {
                continue;
            }
            double d = e.getLocation().distanceSquared(m.getLocation());
            if (d < bestD) {
                bestD = d;
                best = le;
            }
        }
        return best;
    }

    private void fight(Bot b, Mannequin m, LivingEntity target) {
        double reach = plugin.getConfig().getDouble("combat.reach", 3.0);
        double dist = target.getLocation().distance(m.getLocation());
        if (dist <= reach) {
            face(m, target.getEyeLocation());
            if (b.attackCooldown <= 0) {
                b.attackCooldown = plugin.getConfig().getInt("combat.cooldown-ticks", 12);
                m.swingMainHand();
                target.damage(b.damage, m);
                String victim = target instanceof Player p ? p.getName() : target.getType().name();
                runCommands("integration.on-bot-hit", placeholders(b, m, "victim", victim));
            }
        } else {
            moveToward(b, m, target.getLocation());
        }
    }

    // ------------------------------------------------------------ queue

    private void processQueue(Bot b, Mannequin m) {
        if (b.waitTicks > 0) {
            b.waitTicks--;
            return;
        }
        if (b.walkTarget != null) {
            b.walkTicks++;
            if (moveToward(b, m, b.walkTarget) || b.walkTicks > 600) {
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

    /** Runs one action string: goto x y z | wait ticks | say text | look yaw pitch | look player | place | break | use */
    public void runAction(Bot b, Mannequin m, String action) {
        String[] p = action.trim().split(" ", 2);
        String arg = p.length > 1 ? p[1] : "";
        try {
            switch (p[0].toLowerCase(Locale.ROOT)) {
                case "goto" -> {
                    String[] c = arg.split("\\s+");
                    b.walkTarget = new Location(m.getWorld(),
                            Double.parseDouble(c[0]), Double.parseDouble(c[1]), Double.parseDouble(c[2]));
                    b.walkTicks = 0;
                }
                case "wait" -> b.waitTicks = Integer.parseInt(arg.trim());
                case "say" -> say(b, arg);
                case "look" -> {
                    String[] c = arg.trim().split("\\s+");
                    if (c.length >= 2) {
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
        // bot name and message are inserted as plain text so they can't inject MiniMessage tags
        Bukkit.broadcast(plugin.parse(fmt,
                Placeholder.unparsed("bot", b.name),
                Placeholder.unparsed("message", message)));
    }

    public boolean place(Bot b, Mannequin m) {
        if (!plugin.getConfig().getBoolean("build.enabled", true)) {
            return false;
        }
        ItemStack held = m.getEquipment().getItemInMainHand();
        if (held.getType().isAir() || !held.getType().isBlock() || blocked(held.getType())) {
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
            held.setAmount(held.getAmount() - 1);
            m.getEquipment().setItemInMainHand(held);
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
        return hit.breakNaturally(m.getEquipment().getItemInMainHand());
    }

    private boolean blocked(Material mat) {
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
        ph.put("item", m == null ? "" : m.getEquipment().getItemInMainHand().getType().name());
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
