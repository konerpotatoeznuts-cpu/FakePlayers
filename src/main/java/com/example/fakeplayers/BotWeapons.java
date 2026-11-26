package com.example.fakeplayers;

import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.util.Vector;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mannequin;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.Locale;
import java.util.Map;

/**
 * Bridge to the custom weapons plugin (or any item-ability plugin) through config.yml integration.weapons.
 * A weapon entry is matched against the item a bot holds by display name, item type or any persistent-data key.
 * The bot then prefers it, runs its on-hit commands on every hit and its on-use commands as an ability.
 */
public final class BotWeapons {

    private final FakePlayersPlugin plugin;
    private final BotManager mgr;
    private YamlConfiguration cfg = new YamlConfiguration();

    public BotWeapons(FakePlayersPlugin plugin, BotManager mgr) {
        this.plugin = plugin;
        this.mgr = mgr;
        reload();
    }

    /** Loads weapons.yml (created on first start). */
    public void reload() {
        java.io.File f = new java.io.File(plugin.getDataFolder(), "weapons.yml");
        if (!f.exists()) {
            plugin.saveResource("weapons.yml", false);
        }
        cfg = YamlConfiguration.loadConfiguration(f);
    }

    private void run(String path, Map<String, String> ph) {
        for (String c : cfg.getStringList(path)) {
            for (Map.Entry<String, String> en : ph.entrySet()) {
                c = c.replace("%" + en.getKey() + "%", en.getValue());
            }
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), c.startsWith("/") ? c.substring(1) : c);
        }
    }

    private static final org.bukkit.NamespacedKey CW_KEY = new org.bukkit.NamespacedKey("customweapons", "weapon");

    /** The CustomWeapons id (durandal, eldritch_mace, ...) stored on the item, or null. */
    public static String cwId(ItemStack it) {
        if (Bot.empty(it) || !it.hasItemMeta()) {
            return null;
        }
        return it.getItemMeta().getPersistentDataContainer().get(CW_KEY, org.bukkit.persistence.PersistentDataType.STRING);
    }

    private org.bukkit.configuration.file.FileConfiguration cw() {
        org.bukkit.plugin.Plugin p = Bukkit.getPluginManager().getPlugin("CustomWeapons");
        return p == null ? new YamlConfiguration() : p.getConfig();
    }

    /** Config key of the first weapon entry that matches the item, or null. */
    public String match(ItemStack it) {
        if (Bot.empty(it)) {
            return null;
        }
        String id = cwId(it);
        if (id != null) {
            ConfigurationSection w = cfg.getConfigurationSection("weapons");
            if (w != null) {
                for (String key : w.getKeys(false)) {
                    if (key.replace('-', '_').equalsIgnoreCase(id)) {
                        return key;
                    }
                }
            }
            return id;
        }
        ConfigurationSection s = cfg.getConfigurationSection("weapons");
        if (s == null) {
            return null;
        }
        ItemMeta meta = it.getItemMeta();
        String name = meta != null && meta.hasDisplayName()
                ? PlainTextComponentSerializer.plainText().serialize(meta.displayName()).toLowerCase(Locale.ROOT) : "";
        for (String key : s.getKeys(false)) {
            String m = s.getString(key + ".match", key).toLowerCase(Locale.ROOT);
            if (m.isEmpty()) {
                continue;
            }
            if (!name.isEmpty() && name.contains(m) || it.getType().name().toLowerCase(Locale.ROOT).equals(m)) {
                return key;
            }
            if (meta != null) {
                for (org.bukkit.NamespacedKey k : meta.getPersistentDataContainer().getKeys()) {
                    if (k.toString().toLowerCase(Locale.ROOT).contains(m)) {
                        return key;
                    }
                }
            }
        }
        return null;
    }

    public boolean preferred(ItemStack it) {
        String k = match(it);
        return k != null && cfg.getBoolean("weapons." + k + ".prefer", true);
    }

    public void onHit(Bot b, Mannequin m, LivingEntity victim) {
        String k = match(b.held());
        if (k != null) {
            run("weapons." + k + ".on-hit", ph(b, m, victim, k));
            if (cfg.getBoolean("native-abilities", true)) {
                nativeHit(b, m, victim, k.replace('-', '_'));
            }
        }
    }

    /** Runs the held weapon's ability commands when the situation fits. Returns true if one fired. */
    public boolean tryUse(Bot b, Mannequin m, LivingEntity victim, double dist) {
        String k = match(b.held());
        if (k == null) {
            return false;
        }
        if (cfg.getBoolean("native-abilities", true) && nativeUse(b, m, victim, dist, k.replace('-', '_'))) {
            return true;
        }
        String base = "weapons." + k;
        ConfigurationSection s = cfg.getConfigurationSection(base);
        if (s == null || s.getStringList("on-use").isEmpty() || !b.st.ready("wpn:" + k)) {
            return false;
        }
        if (dist > s.getDouble("use-range", 6.0) || dist < s.getDouble("use-min-range", 0.0)) {
            return false;
        }
        String when = s.getString("use-when", "combat");
        double hp = m.getHealth();
        if (when.equals("low-health") && hp > s.getDouble("use-health", 10.0)) {
            return false;
        }
        if (when.equals("airborne") && m.isOnGround()) {
            return false;
        }
        if (when.equals("target-airborne") && victim.isOnGround()) {
            return false;
        }
        if (java.util.concurrent.ThreadLocalRandom.current().nextDouble() > s.getDouble("use-chance", 1.0)) {
            b.st.setCd("wpn:" + k, 10);
            return false;
        }
        b.st.setCd("wpn:" + k, Math.max(1, s.getInt("use-cooldown-ticks", 100)));
        mgr.face(m, victim.getEyeLocation());
        m.swingMainHand();
        run(base + ".on-use", ph(b, m, victim, k));
        return true;
    }


    // ------------------------------------------------------------ native bot versions of the CustomWeapons abilities

    private double w(String id, String key, double def) {
        return cw().getDouble("weapons." + id + "." + key, def);
    }

    private boolean fair(Mannequin m, LivingEntity e, LivingEntity victim) {
        if (e.equals(m) || e.isDead() || e instanceof org.bukkit.entity.ArmorStand) {
            return false;
        }
        if (e.equals(victim)) {
            return true;
        }
        if (e instanceof Player p) {
            return p.getGameMode() == org.bukkit.GameMode.SURVIVAL || p.getGameMode() == org.bukkit.GameMode.ADVENTURE;
        }
        return e instanceof org.bukkit.entity.Monster;
    }

    /** Ability damage like CustomWeapons: multiplied, with a share that ignores armor. */
    private void abilityDamage(Mannequin m, LivingEntity e, double raw) {
        double total = raw * cw().getDouble("damage.ability-multiplier", 2.5);
        double pierce = total * cw().getDouble("damage.armor-piercing-share", 0.6);
        e.damage(total - pierce, m);
        if (!e.isDead() && pierce > 0) {
            e.setNoDamageTicks(0);
            double hp = e.getHealth() - pierce;
            if (hp <= 0) {
                e.damage(1000.0, m);
            } else {
                e.setHealth(hp);
            }
        }
    }

    private java.util.List<LivingEntity> around(Mannequin m, Location c, double r, LivingEntity victim) {
        java.util.List<LivingEntity> out = new java.util.ArrayList<>();
        for (org.bukkit.entity.Entity en : c.getWorld().getNearbyEntities(c, r, r, r)) {
            if (en instanceof LivingEntity le && fair(m, le, victim)) {
                out.add(le);
            }
        }
        return out;
    }

    private void nativeHit(Bot b, Mannequin m, LivingEntity v, String id) {
        java.util.concurrent.ThreadLocalRandom rnd = java.util.concurrent.ThreadLocalRandom.current();
        switch (id) {
            case "durandal" -> {
                double heal = w(id, "lifesteal-hearts", 2) ;
                var ai = m.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH);
                m.setHealth(Math.min(ai == null ? 20.0 : ai.getValue(), m.getHealth() + heal));
                if (rnd.nextDouble() < 0.25) {
                    v.getWorld().strikeLightningEffect(v.getLocation());
                    abilityDamage(m, v, w(id, "lightning-damage", 5.0) / cw().getDouble("damage.ability-multiplier", 2.5));
                }
            }
            case "eldritch_mace" -> {
                if (rnd.nextDouble() < w(id, "proc-chance", 0.4)) {
                    double r = w(id, "proc-radius", 4.0);
                    for (LivingEntity e : around(m, v.getLocation(), r, v)) {
                        abilityDamage(m, e, w(id, "proc-damage", 2.0));
                        e.setVelocity(e.getVelocity().add(new Vector(0, 0.5, 0)));
                    }
                    v.getWorld().spawnParticle(org.bukkit.Particle.EXPLOSION, v.getLocation(), 3, 1, 0.2, 1, 0);
                    v.getWorld().playSound(v.getLocation(), org.bukkit.Sound.ENTITY_GENERIC_EXPLODE, 1f, 1.2f);
                }
            }
            case "halberd_of_despair" -> {
                int secs = (int) (w(id, "stun-seconds", 1.5) * 20);
                v.addPotionEffect(new org.bukkit.potion.PotionEffect(org.bukkit.potion.PotionEffectType.SLOWNESS, secs, 6));
                v.addPotionEffect(new org.bukkit.potion.PotionEffect(org.bukkit.potion.PotionEffectType.JUMP_BOOST, secs, 128));
                if (v instanceof Player p && p.isBlocking()) {
                    p.setCooldown(Material.SHIELD, (int) (w(id, "shield-disable-seconds", 5) * 20));
                }
            }
            default -> {
            }
        }
    }

    private boolean nativeUse(Bot b, Mannequin m, LivingEntity v, double dist, String id) {
        Bot.CombatState st = b.st;
        String cd = "cw:" + id;
        if (!st.ready(cd)) {
            return false;
        }
        switch (id) {
            case "durandal" -> {
                double range = Math.min(w(id, "beam-range", 100), 40);
                if (dist > range || dist < 4) {
                    return false;
                }
                st.setCd(cd, (int) (w(id, "beam-cooldown", 60) * 20));
                heavenlyBeam(m, v, id);
            }
            case "eldritch_mace" -> {
                if (dist < 6 || dist > 30 || !m.isOnGround()) {
                    return false;
                }
                st.setCd(cd, (int) (w(id, "dash-cooldown", 20) * 20));
                Vector dir = v.getLocation().toVector().subtract(m.getLocation().toVector()).setY(0.25).normalize();
                m.setVelocity(dir.multiply(Math.min(1.6, w(id, "dash-power", 2.4) * 0.7)));
                mgr.face(m, v.getEyeLocation());
                m.getWorld().playSound(m.getLocation(), org.bukkit.Sound.ENTITY_BREEZE_WIND_BURST, 1f, 1f);
            }
            case "halberd_of_despair" -> {
                double r = w(id, "spin-radius", 4.5);
                if (dist > r) {
                    return false;
                }
                st.setCd(cd, (int) (w(id, "spin-cooldown", 30) * 20));
                spin(m, v, id, r);
            }
            case "prismatic_blade" -> {
                double r = w(id, "radius", 10.0);
                if (dist > r * 0.7) {
                    return false;
                }
                st.setCd(cd, (int) (w(id, "cooldown", 15) * 20));
                for (LivingEntity e : around(m, m.getLocation(), r, v)) {
                    e.addPotionEffect(new org.bukkit.potion.PotionEffect(org.bukkit.potion.PotionEffectType.BLINDNESS,
                            (int) (w(id, "blind-seconds", 6) * 20), 0));
                    e.addPotionEffect(new org.bukkit.potion.PotionEffect(org.bukkit.potion.PotionEffectType.SLOWNESS,
                            (int) (w(id, "immobilize-seconds", 8) * 20), (int) w(id, "immobilize-slowness-level", 3) - 1));
                    e.addPotionEffect(new org.bukkit.potion.PotionEffect(org.bukkit.potion.PotionEffectType.JUMP_BOOST,
                            (int) (w(id, "immobilize-seconds", 8) * 20), 128));
                }
                m.getWorld().spawnParticle(org.bukkit.Particle.FLASH, m.getEyeLocation(), 1);
                m.getWorld().playSound(m.getLocation(), org.bukkit.Sound.ENTITY_LIGHTNING_BOLT_THUNDER, 1f, 2f);
            }
            case "warden_whip" -> {
                double range = w(id, "range", 16);
                if (dist > range || dist < 3) {
                    return false;
                }
                st.setCd(cd, (int) (w(id, "cooldown", 30) * 20));
                Location from = m.getEyeLocation();
                Vector d = v.getEyeLocation().toVector().subtract(from.toVector());
                double len = d.length();
                d.normalize();
                for (double t = 0; t < len; t += 0.7) {
                    from.getWorld().spawnParticle(org.bukkit.Particle.SONIC_BOOM, from.clone().add(d.clone().multiply(t)), 1, 0, 0, 0, 0);
                }
                from.getWorld().playSound(from, org.bukkit.Sound.ENTITY_WARDEN_SONIC_BOOM, 1f, 1f);
                double dmg = w(id, "damage", 10.0);
                v.damage(0.01, m);
                double hp = v.getHealth() - dmg;
                if (hp <= 0) {
                    v.damage(1000.0, m);
                } else {
                    v.setHealth(hp);
                }
            }
            case "excalibur" -> {
                if (dist <= w(id, "uppercut-range", 2.0) + 1.5) {
                    st.setCd(cd, (int) (w(id, "uppercut-cooldown", 15) * 20));
                    for (LivingEntity e : around(m, m.getLocation().add(m.getLocation().getDirection().multiply(1.5)), 3, v)) {
                        abilityDamage(m, e, w(id, "uppercut-damage", 6.0));
                        e.setVelocity(new Vector(0, w(id, "uppercut-launch", 1.0), 0));
                    }
                    m.swingMainHand();
                } else if (dist >= 4 && dist <= w(id, "dash-distance", 7.0) + 3 && m.isOnGround()) {
                    st.setCd(cd, (int) (w(id, "cooldown", 15) * 20));
                    Vector dir = v.getLocation().toVector().subtract(m.getLocation().toVector()).setY(0.1).normalize();
                    m.setVelocity(dir.multiply(1.5));
                    mgr.face(m, v.getEyeLocation());
                    Bukkit.getScheduler().runTaskLater(plugin, () -> {
                        if (!v.isDead() && m.getLocation().distance(v.getLocation()) < 3.5) {
                            abilityDamage(m, v, w(id, "damage", 6.0));
                        }
                    }, 6L);
                } else {
                    return false;
                }
            }
            default -> {
                return false;
            }
        }
        return true;
    }

    private void heavenlyBeam(Mannequin m, LivingEntity v, String id) {
        Location c = v.getLocation().clone();
        double r = Math.min(w(id, "beam-radius", 10), 8);
        int total = (int) (w(id, "beam-seconds", 5) * 20);
        int every = Math.max(1, (int) (w(id, "beam-damage-interval", 1.0) * 20));
        double dmg = w(id, "beam-damage", 2.0);
        mgr.face(m, v.getEyeLocation());
        m.swingMainHand();
        new org.bukkit.scheduler.BukkitRunnable() {
            int t = 0;

            @Override
            public void run() {
                if (t >= total || m.isDead()) {
                    cancel();
                    return;
                }
                for (int y = 0; y < 30; y += 2) {
                    c.getWorld().spawnParticle(org.bukkit.Particle.END_ROD, c.clone().add(0, y, 0), 4, r * 0.3, 0.2, r * 0.3, 0.01);
                }
                if (t % every == 0) {
                    for (LivingEntity e : around(m, c, r, v)) {
                        abilityDamage(m, e, dmg);
                    }
                }
                t += 2;
            }
        }.runTaskTimer(plugin, 0L, 2L);
    }

    private void spin(Mannequin m, LivingEntity v, String id, double r) {
        int total = (int) (w(id, "spin-seconds", 5) * 20);
        int every = Math.max(1, (int) (w(id, "spin-damage-interval", 1.0) * 20));
        double dmg = w(id, "spin-damage", 2.0);
        int wither = (int) (w(id, "spin-wither-seconds", 2.5) * 20);
        new org.bukkit.scheduler.BukkitRunnable() {
            int t = 0;

            @Override
            public void run() {
                if (t >= total || m.isDead()) {
                    cancel();
                    return;
                }
                Location l = m.getLocation();
                l.setYaw(l.getYaw() + 40f);
                m.setRotation(l.getYaw(), l.getPitch());
                m.getWorld().spawnParticle(org.bukkit.Particle.SWEEP_ATTACK, m.getLocation().add(0, 1, 0), 2, r * 0.4, 0.2, r * 0.4, 0);
                if (t % every == 0) {
                    for (LivingEntity e : around(m, m.getLocation(), r, v)) {
                        abilityDamage(m, e, dmg);
                        e.addPotionEffect(new org.bukkit.potion.PotionEffect(org.bukkit.potion.PotionEffectType.WITHER, wither, 0));
                    }
                }
                t += 2;
            }
        }.runTaskTimer(plugin, 0L, 2L);
    }

    private Map<String, String> ph(Bot b, Mannequin m, LivingEntity victim, String weapon) {
        Map<String, String> ph = mgr.placeholders(b, m, "victim", victim instanceof Player p ? p.getName() : victim.getType().name());
        ph.put("weapon", weapon);
        ph.put("victimuuid", victim.getUniqueId().toString());
        ph.put("botuuid", m.getUniqueId().toString());
        return ph;
    }
}
