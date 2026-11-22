package com.example.fakeplayers;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.type.RespawnAnchor;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.AbstractArrow;
import org.bukkit.entity.Arrow;
import org.bukkit.entity.EnderCrystal;
import org.bukkit.entity.EnderPearl;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mannequin;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Player;
import org.bukkit.entity.SplashPotion;
import org.bukkit.entity.Trident;
import org.bukkit.entity.WindCharge;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.potion.PotionType;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Combat brain for bots. Everything is driven by PvpOptions, so each module can be tuned per bot.
 * Actions are emulated (the bot is a mannequin, not a client), so the numbers are tuned to feel like a player.
 */
public class BotCombat {

    private static final PotionEffectType[] BUFFS = {
            PotionEffectType.SPEED, PotionEffectType.STRENGTH, PotionEffectType.RESISTANCE,
            PotionEffectType.FIRE_RESISTANCE, PotionEffectType.REGENERATION, PotionEffectType.ABSORPTION,
            PotionEffectType.JUMP_BOOST, PotionEffectType.HEALTH_BOOST
    };

    private final FakePlayersPlugin plugin;
    private final BotManager mgr;

    public BotCombat(FakePlayersPlugin plugin, BotManager mgr) {
        this.plugin = plugin;
        this.mgr = mgr;
    }

    // ------------------------------------------------------------ option shortcuts

    private boolean on(Bot b, String p) {
        return PvpOptions.bool(plugin, b, p);
    }

    private double num(Bot b, String p) {
        return PvpOptions.dbl(plugin, b, p);
    }

    private int intv(Bot b, String p) {
        return PvpOptions.integer(plugin, b, p);
    }

    private String str(Bot b, String p) {
        return PvpOptions.str(plugin, b, p);
    }

    private static double rnd() {
        return ThreadLocalRandom.current().nextDouble();
    }

    // ------------------------------------------------------------ main tick

    /** Returns true while the bot is busy fighting, which stops follow and queue behaviour. */
    public boolean tick(Bot b, Mannequin m) {
        Bot.CombatState st = b.st;
        st.cd.replaceAll((k, v) -> v > 0 ? v - 1 : 0);
        if (st.attackTimer > 0) {
            st.attackTimer--;
        }
        if (st.shieldDisabled > 0) {
            st.shieldDisabled--;
        }
        if (st.jumpedTicks > 0) {
            st.jumpedTicks--;
        }

        waterClutch(b, m);
        ensureTotem(b);
        handleEating(b, m);

        LivingEntity t = resolveTarget(b, m);
        if (t == null) {
            st.shielding = false;
            st.drawTicks = 0;
            return st.eatTicks > 0;
        }

        double dist = reachDist(m, t);
        double center = m.getLocation().distance(t.getLocation());

        if (st.reaction > 0) {
            st.reaction--;
            aimAt(b, m, aimPoint(t));
            return true;
        }

        updateShield(b, m, dist);

        if (st.eatTicks > 0) {
            engage(b, m, t, 0.4);
            aimAt(b, m, aimPoint(t));
            return true;
        }

        if (heal(b, m)) {
            return true;
        }
        buff(b, m, center);

        if (pearl(b, m, t, center)) {
            return true;
        }
        if (!web(b, m, t, center) && !lava(b, m, t, center) && !crystal(b, m, t, center)) {
            anchor(b, m, t, center);
        }
        mace(b, m, t, center);

        if (ranged(b, m, t, center)) {
            engage(b, m, t, 0.8);
            return true;
        }

        selectWeapon(b, m, t);
        aimAt(b, m, aimPoint(t));
        engage(b, m, t, 1.0);
        melee(b, m, t, dist);
        return true;
    }

    // ------------------------------------------------------------ targeting

    private LivingEntity resolveTarget(Bot b, Mannequin m) {
        Bot.CombatState st = b.st;

        if (b.forcedTarget != null) {
            Player p = Bukkit.getPlayerExact(b.forcedTarget);
            if (p == null || p.isDead() || !p.getWorld().equals(m.getWorld())) {
                st.targetId = null;
                return null;
            }
            if (!p.getUniqueId().equals(st.targetId)) {
                st.targetId = p.getUniqueId();
                st.reaction = Math.max(0, intv(b, "aim.reaction-ticks"));
            }
            return p;
        }

        LivingEntity cur = null;
        if (st.targetId != null) {
            Entity e = Bukkit.getEntity(st.targetId);
            if (e instanceof LivingEntity le && valid(b, m, le, true)) {
                cur = le;
            } else {
                st.targetId = null;
            }
        }
        boolean lowest = "lowest-health".equals(str(b, "target.mode"));
        if (cur == null || (lowest && --st.acquireTimer <= 0)) {
            st.acquireTimer = 20;
            LivingEntity best = scan(b, m, lowest);
            if (best != null && (cur == null || !best.getUniqueId().equals(cur.getUniqueId()))) {
                st.targetId = best.getUniqueId();
                st.reaction = Math.max(0, intv(b, "aim.reaction-ticks"));
                cur = best;
            }
        }
        return cur;
    }

    private LivingEntity scan(Bot b, Mannequin m, boolean lowest) {
        double range = num(b, "target.range");
        LivingEntity best = null;
        double bestScore = Double.MAX_VALUE;
        for (Entity e : m.getNearbyEntities(range, range, range)) {
            if (!(e instanceof LivingEntity le) || !valid(b, m, le, false)) {
                continue;
            }
            double score = lowest ? le.getHealth() : le.getLocation().distanceSquared(m.getLocation());
            if (score < bestScore) {
                bestScore = score;
                best = le;
            }
        }
        return best;
    }

    private boolean valid(Bot b, Mannequin m, LivingEntity le, boolean keeping) {
        if (le.isDead() || !le.isValid() || le.equals(m) || !le.getWorld().equals(m.getWorld())) {
            return false;
        }
        double range = keeping ? num(b, "target.lose-range") : num(b, "target.range");
        if (le.getLocation().distanceSquared(m.getLocation()) > range * range) {
            return false;
        }
        String ignore = "," + str(b, "target.ignore").toLowerCase(Locale.ROOT).replace(" ", "") + ",";
        if (le instanceof Player p) {
            if (!on(b, "target.players")) {
                return false;
            }
            if (p.getGameMode() != GameMode.SURVIVAL && p.getGameMode() != GameMode.ADVENTURE) {
                return false;
            }
            if (p.isOp() && !on(b, "target.ops")) {
                return false;
            }
            return !ignore.contains("," + p.getName().toLowerCase(Locale.ROOT) + ",");
        }
        if (le instanceof Mannequin) {
            Bot other = mgr.botOf(le);
            if (other == null || !on(b, "target.bots")) {
                return false;
            }
            return !ignore.contains("," + other.name.toLowerCase(Locale.ROOT) + ",");
        }
        return le instanceof Monster && on(b, "target.hostiles");
    }

    private LivingEntity currentTarget(Bot b) {
        if (b.st.targetId == null) {
            return null;
        }
        Entity e = Bukkit.getEntity(b.st.targetId);
        return e instanceof LivingEntity le && !le.isDead() ? le : null;
    }

    // ------------------------------------------------------------ geometry & aim

    private static Location aimPoint(LivingEntity t) {
        return t.getLocation().add(0, t.getHeight() * 0.6, 0);
    }

    /** Distance from the bot's eyes to the closest point of the target's hitbox. */
    private static double reachDist(Mannequin m, LivingEntity t) {
        BoundingBox bb = t.getBoundingBox();
        Location eye = m.getEyeLocation();
        double cx = Math.max(bb.getMinX(), Math.min(eye.getX(), bb.getMaxX()));
        double cy = Math.max(bb.getMinY(), Math.min(eye.getY(), bb.getMaxY()));
        double cz = Math.max(bb.getMinZ(), Math.min(eye.getZ(), bb.getMaxZ()));
        return eye.toVector().distance(new Vector(cx, cy, cz));
    }

    private static float wrap(float a) {
        return ((a % 360f) + 540f) % 360f - 180f;
    }

    private static float clamp(float v, float lo, float hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private void aimAt(Bot b, Mannequin m, Location point) {
        Location eye = m.getEyeLocation();
        double dx = point.getX() - eye.getX();
        double dy = point.getY() - eye.getY();
        double dz = point.getZ() - eye.getZ();
        double h = Math.sqrt(dx * dx + dz * dz);
        float wantYaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float wantPitch = (float) -Math.toDegrees(Math.atan2(dy, h));
        double err = num(b, "aim.error") * 0.5;
        wantYaw += (float) ((rnd() - 0.5) * 2 * err);
        wantPitch += (float) ((rnd() - 0.5) * 2 * err);
        Location loc = m.getLocation();
        float step = (float) Math.max(1.0, num(b, "aim.speed"));
        float yaw = loc.getYaw() + clamp(wrap(wantYaw - loc.getYaw()), -step, step);
        float pitch = loc.getPitch() + clamp(wantPitch - loc.getPitch(), -step, step);
        m.setRotation(yaw, pitch);
    }

    private static boolean facing(Mannequin m, Location point, double tolDeg) {
        Location eye = m.getEyeLocation();
        Vector to = point.toVector().subtract(eye.toVector());
        if (to.lengthSquared() < 1e-6) {
            return true;
        }
        double dot = eye.getDirection().normalize().dot(to.normalize());
        return Math.toDegrees(Math.acos(Math.max(-1, Math.min(1, dot)))) <= tolDeg;
    }

    // ------------------------------------------------------------ movement

    private void engage(Bot b, Mannequin m, LivingEntity t, double speedMul) {
        Bot.CombatState st = b.st;
        if (b.immovable) {
            return;
        }
        Location ml = m.getLocation();
        Location tl = t.getLocation();
        double dx = tl.getX() - ml.getX();
        double dz = tl.getZ() - ml.getZ();
        double h = Math.max(1e-4, Math.hypot(dx, dz));
        double ux = dx / h;
        double uz = dz / h;
        double px = -uz;
        double pz = ux;

        double min = num(b, "move.min-distance");
        double max = num(b, "move.max-distance");
        if (b.st.reaction > 0) {
            return;
        }
        double f = h > max ? 1.0 : (h < min ? -1.0 : 0.15);

        String mode = str(b, "move.strafe");
        double s = 0;
        if (!"none".equals(mode) && h < 10) {
            if (--st.strafeTimer <= 0) {
                int interval = Math.max(5, intv(b, "move.strafe-interval"));
                st.strafeTimer = interval / 2 + ThreadLocalRandom.current().nextInt(interval);
                double flip = "random".equals(mode) ? 0.5 : 0.2;
                if (rnd() < flip) {
                    st.strafeDir = -st.strafeDir;
                }
            }
            s = st.strafeDir * 0.9;
        }

        boolean retreat = on(b, "move.retreat") && m.getHealth() <= num(b, "move.retreat-health");
        if (retreat) {
            f = -1.0;
            s *= 0.5;
        }

        double vx = ux * f + px * s;
        double vz = uz * f + pz * s;
        double len = Math.hypot(vx, vz);
        if (len > 1e-4) {
            vx /= len;
            vz /= len;
        }
        boolean sprint = f > 0 && h > 3.0;
        double speed = (sprint ? num(b, "move.sprint-speed") : num(b, "move.speed")) * speedMul;
        mgr.walk(b, m, vx, vz, speed);

        // jump for a critical hit just before the next swing
        if (on(b, "move.jump-crit") && m.isOnGround() && h < 4.5 && !retreat
                && st.attackTimer <= 2 && st.jumpedTicks == 0 && rnd() < num(b, "move.jump-chance") * 0.35) {
            Vector v = m.getVelocity();
            m.setVelocity(new Vector(v.getX(), 0.42, v.getZ()));
            st.jumpedTicks = 14;
        }
    }

    // ------------------------------------------------------------ weapons

    private static boolean isAxe(Material mat) {
        return mat.name().endsWith("_AXE");
    }

    private static boolean matches(String kind, Material mat) {
        String n = mat.name();
        return switch (kind) {
            case "SWORD" -> n.endsWith("_SWORD");
            case "AXE" -> n.endsWith("_AXE");
            case "MACE" -> mat == Material.MACE;
            case "TRIDENT" -> mat == Material.TRIDENT;
            case "SPEAR" -> n.endsWith("_SPEAR");
            case "BOW" -> mat == Material.BOW;
            case "CROSSBOW" -> mat == Material.CROSSBOW;
            default -> false;
        };
    }

    /** Hotbar slot of the best weapon of a kind, or -1. */
    private int findWeapon(Bot b, String kind) {
        int best = -1;
        double bestDmg = -1;
        for (int i = 0; i < 9; i++) {
            ItemStack it = b.inv[i];
            if (Bot.empty(it) || !matches(kind, it.getType())) {
                continue;
            }
            double d = weaponDamage(it);
            if (d > bestDmg) {
                bestDmg = d;
                best = i;
            }
        }
        return best;
    }

    private void select(Bot b, int slot) {
        if (slot >= 0 && slot < 9 && slot != b.selected) {
            b.selected = slot;
            b.equipDirty = true;
            b.st.attackTimer = Math.max(b.st.attackTimer, 3);
        }
    }

    private void selectWeapon(Bot b, Mannequin m, LivingEntity t) {
        if (b.st.eatTicks > 0) {
            return;
        }
        int want = -1;
        if (!m.isOnGround() && on(b, "mace.enabled")) {
            want = findWeapon(b, "MACE");
        }
        if (want < 0 && t instanceof Player p && p.isBlocking() && on(b, "weapon.shield-break")) {
            want = findWeapon(b, "AXE");
        }
        if (want < 0) {
            for (String kind : str(b, "weapon.prefer").split(",")) {
                String k = kind.trim().toUpperCase(Locale.ROOT);
                if (k.equals("MACE") && !on(b, "mace.enabled")) {
                    continue;
                }
                want = findWeapon(b, k);
                if (want >= 0) {
                    break;
                }
            }
        }
        select(b, want);
    }

    /** Base melee damage of an item; custom attribute modifiers on the item replace the vanilla value. */
    private double weaponDamage(ItemStack it) {
        if (Bot.empty(it)) {
            return 1.0;
        }
        double dmg = tableDamage(it.getType());
        ItemMeta meta = it.getItemMeta();
        if (meta != null && meta.hasAttributeModifiers()) {
            Collection<AttributeModifier> mods = meta.getAttributeModifiers(Attribute.ATTACK_DAMAGE);
            if (mods != null && !mods.isEmpty()) {
                double sum = 0;
                for (AttributeModifier am : mods) {
                    if (am.getOperation() == AttributeModifier.Operation.ADD_NUMBER) {
                        sum += am.getAmount();
                    }
                }
                dmg = 1.0 + sum;
            }
        }
        int sharp = it.getEnchantmentLevel(Enchantment.SHARPNESS);
        if (sharp > 0) {
            dmg += 0.5 * sharp + 0.5;
        }
        return dmg;
    }

    private static double tableDamage(Material mat) {
        String n = mat.name();
        if (mat == Material.MACE) {
            return 6;
        }
        if (mat == Material.TRIDENT) {
            return 9;
        }
        int tier = n.startsWith("WOODEN") ? 0 : n.startsWith("GOLDEN") ? 1 : n.startsWith("STONE") ? 2
                : n.startsWith("COPPER") ? 3 : n.startsWith("IRON") ? 4 : n.startsWith("DIAMOND") ? 5
                : n.startsWith("NETHERITE") ? 6 : -1;
        if (tier < 0) {
            return 1;
        }
        if (n.endsWith("_SWORD")) {
            return new double[]{4, 4, 5, 5, 6, 7, 8}[tier];
        }
        if (n.endsWith("_AXE")) {
            return new double[]{7, 7, 9, 9, 9, 9, 10}[tier];
        }
        if (n.endsWith("_SPEAR")) {
            return new double[]{3, 3, 4, 4, 5, 6, 7}[tier];
        }
        return 1;
    }

    /** Java attack cooldown in ticks for an item. */
    private int cooldownTicks(Bot b, ItemStack it) {
        int fixed = intv(b, "hit.cooldown-ticks");
        if (fixed >= 0) {
            return fixed;
        }
        if (Bot.empty(it)) {
            return 5;
        }
        Material mat = it.getType();
        String n = mat.name();
        if (mat == Material.MACE) {
            return 34;
        }
        if (mat == Material.TRIDENT) {
            return 19;
        }
        if (n.endsWith("_AXE")) {
            if (n.startsWith("WOODEN") || n.startsWith("STONE") || n.startsWith("GOLDEN")) {
                return 25;
            }
            return n.startsWith("IRON") ? 22 : 20;
        }
        if (n.endsWith("_SWORD")) {
            return 13;
        }
        return 5;
    }

    // ------------------------------------------------------------ melee

    private void melee(Bot b, Mannequin m, LivingEntity t, double dist) {
        Bot.CombatState st = b.st;
        if (dist > num(b, "hit.reach") || st.shielding || st.attackTimer > 0) {
            return;
        }
        if (!facing(m, aimPoint(t), 30)) {
            return;
        }
        // after a jump, wait until the bot is falling so the hit is a critical
        if (on(b, "hit.crit") && st.jumpedTicks > 0 && !m.isOnGround() && m.getVelocity().getY() > 0) {
            return;
        }
        if (b.platform == Bot.Platform.BEDROCK) {
            double cps = Math.max(1.0, num(b, "hit.cps"));
            st.attackTimer = Math.max(1, (int) Math.round(20.0 / cps) + ThreadLocalRandom.current().nextInt(-1, 2));
        } else {
            st.attackTimer = cooldownTicks(b, b.held());
        }
        if (rnd() < num(b, "hit.miss-chance")) {
            m.swingMainHand();
            return;
        }
        hit(b, m, t);
    }

    private static double maceBonus(double fall) {
        double bonus = 4.0 * Math.min(fall, 3.0);
        if (fall > 3) {
            bonus += 2.0 * Math.min(fall - 3.0, 5.0);
        }
        if (fall > 8) {
            bonus += fall - 8.0;
        }
        return bonus;
    }

    private void hit(Bot b, Mannequin m, LivingEntity t) {
        ItemStack w = b.held();
        Material wt = w == null ? Material.AIR : w.getType();
        double dmg = weaponDamage(w);

        PotionEffect strength = m.getPotionEffect(PotionEffectType.STRENGTH);
        if (strength != null) {
            dmg += 3.0 * (strength.getAmplifier() + 1);
        }
        PotionEffect weakness = m.getPotionEffect(PotionEffectType.WEAKNESS);
        if (weakness != null) {
            dmg -= 4.0 * (weakness.getAmplifier() + 1);
        }
        dmg = Math.max(1.0, dmg);

        double fall = m.getFallDistance();
        boolean smash = wt == Material.MACE && fall > 1.5 && !m.isOnGround();
        boolean crit = on(b, "hit.crit") && !smash && !m.isOnGround() && fall > 0 && m.getVelocity().getY() < 0;
        if (crit) {
            dmg *= 1.5;
        }
        if (smash) {
            dmg += maceBonus(fall);
            int density = w.getEnchantmentLevel(Enchantment.DENSITY);
            if (density > 0) {
                dmg += 0.5 * density * fall;
            }
        }

        if (t instanceof Player p && p.isBlocking() && isAxe(wt)) {
            p.setCooldown(Material.SHIELD, intv(b, "shield.axe-disable-ticks"));
            p.clearActiveItem();
        }

        m.swingMainHand();
        t.damage(dmg, m);

        double extra = num(b, "hit.knockback-extra");
        if (extra > 0) {
            Vector dir = t.getLocation().toVector().subtract(m.getLocation().toVector()).setY(0);
            if (dir.lengthSquared() > 1e-6) {
                t.setVelocity(t.getVelocity().add(dir.normalize().multiply(extra)));
            }
        }
        if (smash) {
            m.setFallDistance(0f);
            int wind = w.getEnchantmentLevel(Enchantment.WIND_BURST);
            if (wind > 0) {
                Vector v = m.getVelocity();
                m.setVelocity(new Vector(v.getX(), 1.0 + 0.4 * wind, v.getZ()));
            }
        }
        String victim = t instanceof Player p ? p.getName() : t.getType().name();
        mgr.runCommands("integration.on-bot-hit", mgr.placeholders(b, m, "victim", victim));
    }

    // ------------------------------------------------------------ shield (emulated)

    private static boolean isShield(ItemStack i) {
        return !Bot.empty(i) && i.getType() == Material.SHIELD;
    }

    private void updateShield(Bot b, Mannequin m, double dist) {
        Bot.CombatState st = b.st;
        boolean has = isShield(b.offhand) || isShield(b.held());
        if (!has || !on(b, "shield.enabled") || st.shieldDisabled > 0) {
            st.shielding = false;
            return;
        }
        if (st.shieldTimer > 0) {
            st.shieldTimer--;
            return;
        }
        if (st.shielding) {
            st.shielding = false;
            st.shieldTimer = Math.max(1, intv(b, "shield.lower-ticks"));
            return;
        }
        if (dist <= num(b, "shield.range") && rnd() < num(b, "shield.block-chance")) {
            st.shielding = true;
            st.shieldTimer = Math.max(1, intv(b, "shield.hold-ticks"));
        } else {
            st.shieldTimer = 5;
        }
    }

    // ------------------------------------------------------------ totems

    private void ensureTotem(Bot b) {
        if (!on(b, "totem.refill") || !Bot.empty(b.offhand)) {
            return;
        }
        int s = b.find(Material.TOTEM_OF_UNDYING);
        if (s >= 0) {
            b.take(s, 1);
            b.offhand = new ItemStack(Material.TOTEM_OF_UNDYING);
            b.equipDirty = true;
        }
    }

    /** Called one tick after a totem pop. */
    public void afterTotem(Bot b, Mannequin m) {
        mgr.pullHands(b, m);
        b.equipDirty = true;
        ensureTotem(b);
    }

    // ------------------------------------------------------------ healing & buffs

    private static boolean isHealingPotion(ItemStack i) {
        if (Bot.empty(i) || !(i.getItemMeta() instanceof PotionMeta pm)) {
            return false;
        }
        PotionType base = pm.getBasePotionType();
        if (base == PotionType.HEALING || base == PotionType.STRONG_HEALING) {
            return true;
        }
        for (PotionEffect e : pm.getCustomEffects()) {
            if (e.getType() == PotionEffectType.INSTANT_HEALTH) {
                return true;
            }
        }
        return false;
    }

    private static List<PotionEffect> effectsOf(ItemStack i) {
        List<PotionEffect> out = new ArrayList<>();
        if (!Bot.empty(i) && i.getItemMeta() instanceof PotionMeta pm) {
            PotionType base = pm.getBasePotionType();
            if (base != null) {
                out.addAll(base.getPotionEffects());
            }
            out.addAll(pm.getCustomEffects());
        }
        return out;
    }

    private boolean heal(Bot b, Mannequin m) {
        if (!on(b, "heal.enabled") || b.st.eatTicks > 0) {
            return false;
        }
        double hp = m.getHealth();
        if (hp <= num(b, "heal.egap-health")) {
            int s = b.find(Material.ENCHANTED_GOLDEN_APPLE);
            if (s >= 0) {
                startEat(b, s, "egap");
                return true;
            }
        }
        if (hp <= num(b, "heal.potion-health")) {
            if (on(b, "heal.splash") && b.st.ready("splash")) {
                int s = b.find(i -> i.getType() == Material.SPLASH_POTION && isHealingPotion(i), false);
                if (s >= 0) {
                    throwSplashAtFeet(b, m, s);
                    return true;
                }
            }
            int s = b.find(i -> i.getType() == Material.POTION && isHealingPotion(i), false);
            if (s >= 0) {
                startEat(b, s, "potion");
                return true;
            }
        }
        if (hp <= num(b, "heal.gap-health")) {
            int s = b.find(Material.GOLDEN_APPLE);
            if (s >= 0) {
                startEat(b, s, "gap");
                return true;
            }
        }
        return false;
    }

    private void buff(Bot b, Mannequin m, double dist) {
        if (!on(b, "buff.enabled") || b.st.eatTicks > 0 || !b.st.ready("buff") || dist > 24) {
            return;
        }
        b.st.setCd("buff", intv(b, "buff.cooldown"));
        int s = b.find(i -> {
            if (i.getType() != Material.POTION || isHealingPotion(i)) {
                return false;
            }
            for (PotionEffect e : effectsOf(i)) {
                for (PotionEffectType good : BUFFS) {
                    if (e.getType() == good && !m.hasPotionEffect(good)) {
                        return true;
                    }
                }
            }
            return false;
        }, false);
        if (s >= 0) {
            startEat(b, s, "potion");
        }
    }

    private void startEat(Bot b, int slot, String kind) {
        Bot.CombatState st = b.st;
        st.eatSlot = slot;
        st.eatKind = kind;
        st.eatTicks = Math.max(1, intv(b, "heal.eat-ticks"));
        st.prevSelected = b.selected;
        if (slot < 9) {
            b.selected = slot;
            b.equipDirty = true;
        }
    }

    private void handleEating(Bot b, Mannequin m) {
        Bot.CombatState st = b.st;
        if (st.eatTicks <= 0) {
            return;
        }
        ItemStack item = st.eatSlot >= 0 ? b.inv[st.eatSlot] : null;
        if (Bot.empty(item)) {
            st.eatTicks = 0;
            return;
        }
        st.eatTicks--;
        if (st.eatTicks % 4 == 0) {
            Sound s = st.eatKind.equals("potion") ? Sound.ENTITY_GENERIC_DRINK : Sound.ENTITY_GENERIC_EAT;
            m.getWorld().playSound(m.getLocation(), s, 0.8f, 1.0f);
        }
        if (st.eatTicks > 0) {
            return;
        }
        switch (st.eatKind) {
            case "gap" -> {
                m.addPotionEffect(new PotionEffect(PotionEffectType.REGENERATION, 100, 1));
                m.addPotionEffect(new PotionEffect(PotionEffectType.ABSORPTION, 2400, 0));
                useUp(b, st.eatSlot, null);
            }
            case "egap" -> {
                m.addPotionEffect(new PotionEffect(PotionEffectType.REGENERATION, 400, 1));
                m.addPotionEffect(new PotionEffect(PotionEffectType.ABSORPTION, 2400, 3));
                m.addPotionEffect(new PotionEffect(PotionEffectType.RESISTANCE, 6000, 0));
                m.addPotionEffect(new PotionEffect(PotionEffectType.FIRE_RESISTANCE, 6000, 0));
                useUp(b, st.eatSlot, null);
            }
            default -> {
                for (PotionEffect e : effectsOf(item)) {
                    if (e.getType() == PotionEffectType.INSTANT_HEALTH) {
                        double max = maxHealth(m);
                        m.setHealth(Math.min(max, m.getHealth() + 4.0 * (1 << e.getAmplifier())));
                    } else {
                        m.addPotionEffect(e);
                    }
                }
                useUp(b, st.eatSlot, Material.GLASS_BOTTLE);
            }
        }
        if (st.prevSelected >= 0 && st.prevSelected < 9) {
            b.selected = st.prevSelected;
        }
        b.equipDirty = true;
        st.eatSlot = -1;
    }

    private void useUp(Bot b, int slot, Material leftover) {
        if (!on(b, "items.consume")) {
            return;
        }
        if (leftover != null && b.inv[slot] != null && b.inv[slot].getAmount() == 1) {
            b.inv[slot] = new ItemStack(leftover);
            b.equipDirty = true;
        } else {
            b.take(slot, 1);
        }
    }

    private static double maxHealth(LivingEntity e) {
        AttributeInstance ai = e.getAttribute(Attribute.MAX_HEALTH);
        return ai == null ? 20.0 : ai.getValue();
    }

    private void throwSplashAtFeet(Bot b, Mannequin m, int slot) {
        ItemStack pot = b.inv[slot].clone();
        pot.setAmount(1);
        Location l = m.getLocation();
        m.setRotation(l.getYaw(), 90f);
        SplashPotion sp = m.launchProjectile(SplashPotion.class, new Vector(0, -0.3, 0));
        sp.setItem(pot);
        m.swingMainHand();
        if (on(b, "items.consume")) {
            b.take(slot, 1);
        }
        b.st.setCd("splash", 12);
    }

    // ------------------------------------------------------------ cobweb / lava / water drain

    private static boolean inWeb(LivingEntity t) {
        return t.getLocation().getBlock().getType() == Material.COBWEB
                || t.getEyeLocation().getBlock().getType() == Material.COBWEB;
    }

    private static boolean replaceable(Block bl) {
        return bl.isEmpty() || bl.isReplaceable() || bl.getType() == Material.COBWEB;
    }

    private boolean web(Bot b, Mannequin m, LivingEntity t, double dist) {
        if (!on(b, "web.enabled") || !b.st.ready("web") || dist > Math.min(num(b, "web.range"), 4.5)) {
            return false;
        }
        return placeWeb(b, m, t);
    }

    private boolean placeWeb(Bot b, Mannequin m, LivingEntity t) {
        int slot = b.find(Material.COBWEB);
        if (slot < 0 || inWeb(t)) {
            return false;
        }
        Block tb = t.getLocation().getBlock();
        if (!replaceable(tb) || tb.getType() == Material.COBWEB) {
            return false;
        }
        if (!mgr.placeTemp(b, tb, Material.COBWEB, intv(b, "web.cleanup-ticks"), null, null)) {
            return false;
        }
        if (on(b, "items.consume")) {
            b.take(slot, 1);
        }
        m.swingMainHand();
        b.st.setCd("web", intv(b, "web.cooldown"));
        return true;
    }

    private boolean lava(Bot b, Mannequin m, LivingEntity t, double dist) {
        if (!on(b, "lava.enabled") || !b.st.ready("lava") || dist > Math.min(num(b, "lava.range"), 4.5)) {
            return false;
        }
        if (on(b, "lava.require-web") && !inWeb(t)) {
            return false;
        }
        int slot = b.find(Material.LAVA_BUCKET);
        if (slot < 0) {
            return false;
        }
        Block tb = t.getLocation().getBlock();
        if (!replaceable(tb) || m.getLocation().distance(tb.getLocation().add(0.5, 0, 0.5)) < 1.5) {
            return false;
        }
        Material from = on(b, "lava.retrieve") ? Material.BUCKET : null;
        Material to = on(b, "lava.retrieve") ? Material.LAVA_BUCKET : null;
        if (!mgr.placeTemp(b, tb, Material.LAVA, intv(b, "lava.cleanup-ticks"), from, to)) {
            return false;
        }
        if (on(b, "items.consume")) {
            b.convertOne(slot, Material.BUCKET);
        }
        m.swingMainHand();
        b.st.setCd("lava", intv(b, "lava.cooldown"));
        return true;
    }

    /** Called when a player empties a bucket. Bots targeting that player may drain it. */
    public void onFluidPlaced(Player p, Block block, Material bucket) {
        for (Bot b : new ArrayList<>(mgr.all())) {
            if (!b.combat || !on(b, "drain.enabled") || b.st.targetId == null
                    || !b.st.targetId.equals(p.getUniqueId())) {
                continue;
            }
            Mannequin m = mgr.entity(b);
            if (m == null || !m.getWorld().equals(block.getWorld())) {
                continue;
            }
            boolean water = bucket == Material.WATER_BUCKET;
            boolean lavaBucket = bucket == Material.LAVA_BUCKET;
            if (!water && !(lavaBucket && on(b, "drain.lava"))) {
                continue;
            }
            if (m.getLocation().distance(block.getLocation()) > num(b, "drain.range")) {
                continue;
            }
            int delay = Math.max(1, intv(b, "drain.reaction-ticks") + ThreadLocalRandom.current().nextInt(3));
            Material fluid = water ? Material.WATER : Material.LAVA;
            Bukkit.getScheduler().runTaskLater(plugin, () -> drain(b, block, fluid, p), delay);
        }
    }

    private void drain(Bot b, Block block, Material fluid, Player victim) {
        Mannequin m = mgr.entity(b);
        if (m == null || block.getType() != fluid) {
            return;
        }
        int slot = -1;
        if (on(b, "drain.require-bucket")) {
            slot = b.find(Material.BUCKET);
            if (slot < 0) {
                return;
            }
        }
        mgr.face(m, block.getLocation().add(0.5, 0.5, 0.5));
        m.swingMainHand();
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    Block n = block.getRelative(dx, dy, dz);
                    if (n.getType() == fluid) {
                        n.setType(Material.AIR);
                    }
                }
            }
        }
        if (slot >= 0) {
            b.convertOne(slot, fluid == Material.WATER ? Material.WATER_BUCKET : Material.LAVA_BUCKET);
        }
        if (on(b, "drain.reweb") && victim.isOnline() && !victim.isDead()) {
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                Mannequin mm = mgr.entity(b);
                if (mm != null) {
                    placeWeb(b, mm, victim);
                }
            }, 2L);
        }
    }

    private void waterClutch(Bot b, Mannequin m) {
        if (!on(b, "water.mlg") || m.isOnGround() || m.getFallDistance() < 6 || m.getVelocity().getY() > -0.5
                || !b.st.ready("mlg")) {
            return;
        }
        int slot = b.find(Material.WATER_BUCKET);
        if (slot < 0) {
            return;
        }
        RayTraceResult r = m.getWorld().rayTraceBlocks(m.getLocation(), new Vector(0, -1, 0), 3.5);
        if (r == null || r.getHitBlock() == null || r.getHitBlock().getType() == Material.WATER) {
            return;
        }
        Block above = r.getHitBlock().getRelative(BlockFace.UP);
        if (!replaceable(above)) {
            return;
        }
        if (mgr.placeTemp(b, above, Material.WATER, 40, Material.BUCKET, Material.WATER_BUCKET)) {
            Location l = m.getLocation();
            m.setRotation(l.getYaw(), 90f);
            m.swingMainHand();
            b.convertOne(slot, Material.BUCKET);
            b.st.setCd("mlg", 40);
        }
    }

    // ------------------------------------------------------------ explosions: crystals and anchors

    private static double attr(LivingEntity e, Attribute a) {
        AttributeInstance ai = e.getAttribute(a);
        return ai == null ? 0 : ai.getValue();
    }

    /** Rough explosion damage estimate (ignores line of sight and protection enchantments). */
    private static double estimate(double power, Location center, LivingEntity e) {
        if (!e.getWorld().equals(center.getWorld())) {
            return 0;
        }
        double r = power * 2.0;
        Location el = e.getLocation().add(0, e.getHeight() / 2.0, 0);
        double d = el.distance(center);
        if (d >= r) {
            return 0;
        }
        double impact = 1.0 - d / r;
        double raw = (impact * impact + impact) / 2.0 * 7.0 * r + 1.0;
        double armor = attr(e, Attribute.ARMOR);
        double tough = attr(e, Attribute.ARMOR_TOUGHNESS);
        double red = Math.min(20.0, Math.max(armor / 5.0, armor - raw / (2.0 + tough / 4.0)));
        double dmg = raw * (1.0 - red / 25.0);
        PotionEffect res = e.getPotionEffect(PotionEffectType.RESISTANCE);
        if (res != null) {
            dmg *= Math.max(0.0, 1.0 - 0.2 * (res.getAmplifier() + 1));
        }
        return dmg;
    }

    private boolean crystal(Bot b, Mannequin m, LivingEntity t, double dist) {
        Bot.CombatState st = b.st;
        if (!on(b, "crystal.enabled") || !st.ready("crystal") || dist > num(b, "crystal.range")) {
            return false;
        }
        int cslot = b.find(Material.END_CRYSTAL);
        if (cslot < 0) {
            return false;
        }
        st.crystals.removeIf(id -> {
            Entity e = Bukkit.getEntity(id);
            return e == null || !e.isValid();
        });
        if (st.crystals.size() >= intv(b, "crystal.max-active")) {
            return false;
        }

        Block tb = t.getLocation().getBlock();
        double minT = num(b, "crystal.min-target-damage");
        double maxS = Math.min(num(b, "crystal.max-self-damage"), m.getHealth() - 1.0);

        Block bestBase = null;
        double bestScore = -1e9;
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                for (int dy = -1; dy <= 0; dy++) {
                    Block c = tb.getRelative(dx, dy, dz);
                    Material mt = c.getType();
                    if (mt != Material.OBSIDIAN && mt != Material.BEDROCK) {
                        continue;
                    }
                    if (!c.getRelative(0, 1, 0).isEmpty() || !c.getRelative(0, 2, 0).isEmpty()) {
                        continue;
                    }
                    Location ctr = c.getLocation().add(0.5, 1.0, 0.5);
                    double td = estimate(6, ctr, t);
                    double sd = estimate(6, ctr, m);
                    if (td < minT || sd > maxS) {
                        continue;
                    }
                    if (td - sd > bestScore) {
                        bestScore = td - sd;
                        bestBase = c;
                    }
                }
            }
        }

        if (bestBase == null) {
            if (!on(b, "crystal.place-obsidian")) {
                return false;
            }
            int oslot = b.find(Material.OBSIDIAN);
            if (oslot < 0) {
                return false;
            }
            Block bestSpot = null;
            for (int dx = -2; dx <= 2; dx++) {
                for (int dz = -2; dz <= 2; dz++) {
                    Block c = tb.getRelative(dx, 0, dz);
                    if (!replaceable(c) || c.getType() == Material.COBWEB || !c.getRelative(BlockFace.DOWN).getType().isSolid()
                            || !c.getRelative(0, 1, 0).isEmpty() || !c.getRelative(0, 2, 0).isEmpty()) {
                        continue;
                    }
                    if (c.equals(m.getLocation().getBlock()) || c.equals(tb)) {
                        continue;
                    }
                    Location ctr = c.getLocation().add(0.5, 1.0, 0.5);
                    double td = estimate(6, ctr, t);
                    double sd = estimate(6, ctr, m);
                    if (td < minT || sd > maxS) {
                        continue;
                    }
                    if (td - sd > bestScore) {
                        bestScore = td - sd;
                        bestSpot = c;
                    }
                }
            }
            if (bestSpot == null) {
                return false;
            }
            if (!mgr.placeTemp(b, bestSpot, Material.OBSIDIAN, intv(b, "crystal.obsidian-cleanup-ticks"), null, null)) {
                return false;
            }
            if (on(b, "items.consume")) {
                b.take(oslot, 1);
            }
            m.swingMainHand();
            st.setCd("crystal", 2);
            return true;
        }

        Location loc = bestBase.getLocation().add(0.5, 1.0, 0.5);
        EnderCrystal ec = bestBase.getWorld().spawn(loc, EnderCrystal.class, c -> c.setShowingBottom(false));
        st.crystals.add(ec.getUniqueId());
        if (on(b, "items.consume")) {
            b.take(cslot, 1);
        }
        m.swingMainHand();
        st.setCd("crystal", intv(b, "crystal.cooldown"));
        scheduleDetonation(b, ec.getUniqueId(), Math.max(1, intv(b, "crystal.hit-delay")), 8);
        return true;
    }

    private void scheduleDetonation(Bot b, UUID id, int delay, int attempts) {
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            Entity e = Bukkit.getEntity(id);
            if (!(e instanceof EnderCrystal ec) || !ec.isValid()) {
                return;
            }
            Mannequin m = mgr.entity(b);
            if (m == null) {
                ec.remove();
                return;
            }
            LivingEntity t = currentTarget(b);
            Location c = ec.getLocation();
            double self = estimate(6, c, m);
            double tgt = t == null ? 0 : estimate(6, c, t);
            if (tgt >= num(b, "crystal.min-target-damage")
                    && self <= Math.min(num(b, "crystal.max-self-damage"), m.getHealth() - 1.0)) {
                m.swingMainHand();
                ec.remove();
                c.getWorld().createExplosion(c, 6f, false, on(b, "crystal.block-damage"), m);
            } else if (attempts > 0) {
                scheduleDetonation(b, id, 2, attempts - 1);
            } else {
                ec.remove();
            }
        }, delay);
    }

    private boolean anchor(Bot b, Mannequin m, LivingEntity t, double dist) {
        if (!on(b, "anchor.enabled") || !b.st.ready("anchor") || dist > num(b, "anchor.range")) {
            return false;
        }
        int aslot = b.find(Material.RESPAWN_ANCHOR);
        int gslot = b.find(Material.GLOWSTONE);
        if (aslot < 0 || gslot < 0) {
            return false;
        }
        Block tb = t.getLocation().getBlock();
        double minT = num(b, "anchor.min-target-damage");
        double maxS = Math.min(num(b, "anchor.max-self-damage"), m.getHealth() - 1.0);
        Block best = null;
        double bestScore = -1e9;
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                Block c = tb.getRelative(dx, 0, dz);
                if (!replaceable(c) || c.getType() == Material.COBWEB || c.equals(tb) || c.equals(m.getLocation().getBlock())
                        || !c.getRelative(BlockFace.DOWN).getType().isSolid()) {
                    continue;
                }
                Location ctr = c.getLocation().add(0.5, 0.5, 0.5);
                double td = estimate(5, ctr, t);
                double sd = estimate(5, ctr, m);
                if (td < minT || sd > maxS) {
                    continue;
                }
                if (td - sd > bestScore) {
                    bestScore = td - sd;
                    best = c;
                }
            }
        }
        if (best == null) {
            return false;
        }
        final Block spot = best;
        if (!mgr.placeTemp(b, spot, Material.RESPAWN_ANCHOR, 100, null, null)) {
            return false;
        }
        if (spot.getBlockData() instanceof RespawnAnchor ra) {
            ra.setCharges(1);
            spot.setBlockData(ra);
        }
        if (on(b, "items.consume")) {
            b.take(aslot, 1);
            b.take(b.find(Material.GLOWSTONE), 1);
        }
        m.swingMainHand();
        b.st.setCd("anchor", intv(b, "anchor.cooldown"));
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            Mannequin mm = mgr.entity(b);
            if (mm == null || spot.getType() != Material.RESPAWN_ANCHOR) {
                return;
            }
            LivingEntity tt = currentTarget(b);
            Location ctr = spot.getLocation().add(0.5, 0.5, 0.5);
            double td = tt == null ? 0 : estimate(5, ctr, tt);
            double sd = estimate(5, ctr, mm);
            spot.setType(Material.AIR);
            if (td >= minT && sd <= Math.min(num(b, "anchor.max-self-damage"), mm.getHealth() - 1.0)) {
                mm.swingMainHand();
                ctr.getWorld().createExplosion(ctr, 5f, false, on(b, "anchor.block-damage"), mm);
            }
        }, Math.max(1, intv(b, "anchor.explode-delay")));
        return true;
    }

    // ------------------------------------------------------------ mace

    private void mace(Bot b, Mannequin m, LivingEntity t, double dist) {
        if (!on(b, "mace.enabled")) {
            return;
        }
        int maceSlot = findWeapon(b, "MACE");
        if (maceSlot < 0) {
            return;
        }
        Location ml = m.getLocation();
        Location tl = t.getLocation();
        Vector v = m.getVelocity();

        if (!m.isOnGround()) {
            double dx = tl.getX() - ml.getX();
            double dz = tl.getZ() - ml.getZ();
            double h = Math.hypot(dx, dz);
            if (h > 0.8) {
                double nx = v.getX() + dx / h * 0.06;
                double nz = v.getZ() + dz / h * 0.06;
                double hs = Math.hypot(nx, nz);
                if (hs > 0.45) {
                    nx = nx / hs * 0.45;
                    nz = nz / hs * 0.45;
                }
                v.setX(nx);
                v.setZ(nz);
            }
            if (on(b, "mace.dive") && v.getY() < 0 && h < 2.0 && ml.getY() > tl.getY() + 2.0) {
                v.setY(Math.max(v.getY() - 0.1, -1.2));
            }
            m.setVelocity(v);
            select(b, maceSlot);
            return;
        }

        if (!b.st.ready("mace") || dist > num(b, "mace.launch-range") || dist < 2.0 || b.immovable) {
            return;
        }
        if (on(b, "mace.wind-charge")) {
            int wc = b.find(Material.WIND_CHARGE);
            if (wc < 0) {
                return;
            }
            m.setRotation(ml.getYaw(), 90f);
            m.setVelocity(new Vector(v.getX(), 0.42, v.getZ()));
            m.launchProjectile(WindCharge.class, new Vector(0, -1, 0));
            m.swingMainHand();
            if (on(b, "items.consume")) {
                b.take(wc, 1);
            }
        } else {
            m.setVelocity(new Vector(v.getX(), 0.7, v.getZ()));
        }
        b.st.setCd("mace", intv(b, "mace.cooldown"));
        select(b, maceSlot);
    }

    // ------------------------------------------------------------ ranged

    private boolean ranged(Bot b, Mannequin m, LivingEntity t, double dist) {
        Bot.CombatState st = b.st;
        if (!on(b, "ranged.enabled") || dist < num(b, "ranged.min-range") || dist > num(b, "ranged.max-range")) {
            st.drawTicks = 0;
            return false;
        }
        int slot = findWeapon(b, "BOW");
        if (slot < 0) {
            slot = findWeapon(b, "CROSSBOW");
        }
        if (slot < 0 && on(b, "ranged.trident-throw")) {
            slot = findWeapon(b, "TRIDENT");
        }
        if (slot < 0) {
            st.drawTicks = 0;
            return false;
        }
        Material mat = b.inv[slot].getType();
        int arrowSlot = -1;
        if (mat != Material.TRIDENT && !on(b, "ranged.infinite-ammo")) {
            arrowSlot = b.find(i -> i.getType() == Material.ARROW || i.getType() == Material.SPECTRAL_ARROW
                    || i.getType() == Material.TIPPED_ARROW);
            if (arrowSlot < 0) {
                st.drawTicks = 0;
                return false;
            }
        }
        select(b, slot);

        double speed = mat == Material.TRIDENT ? 2.5 : 3.0;
        double flight = dist / speed;
        Location p = aimPoint(t);
        p.add(t.getVelocity().multiply(flight));
        p.add(0, 0.025 * flight * flight, 0);
        aimAt(b, m, p);

        int need = mat == Material.CROSSBOW ? 25 : mat == Material.TRIDENT ? 10 : Math.max(3, intv(b, "ranged.draw-ticks"));
        if (++st.drawTicks < need) {
            return true;
        }
        st.drawTicks = 0;

        Vector dir = p.toVector().subtract(m.getEyeLocation().toVector()).normalize();
        double spread = num(b, "ranged.spread");
        dir.rotateAroundY(Math.toRadians(ThreadLocalRandom.current().nextGaussian() * spread));
        dir.setY(dir.getY() + Math.toRadians(ThreadLocalRandom.current().nextGaussian() * spread));
        dir.normalize().multiply(speed);

        m.swingMainHand();
        if (mat == Material.TRIDENT) {
            Trident tr = m.launchProjectile(Trident.class, dir);
            tr.setPickupStatus(AbstractArrow.PickupStatus.DISALLOWED);
        } else {
            Arrow arrow = m.launchProjectile(Arrow.class, dir);
            ItemStack bow = b.inv[slot];
            int power = bow.getEnchantmentLevel(Enchantment.POWER);
            arrow.setDamage(2.0 + (power > 0 ? 0.5 * power + 0.5 : 0));
            arrow.setCritical(true);
            arrow.setPickupStatus(AbstractArrow.PickupStatus.DISALLOWED);
            if (arrowSlot >= 0 && on(b, "items.consume")) {
                b.take(arrowSlot, 1);
            }
        }
        return true;
    }

    // ------------------------------------------------------------ pearls

    private boolean pearl(Bot b, Mannequin m, LivingEntity t, double dist) {
        if (!on(b, "pearl.enabled") || !b.st.ready("pearl")) {
            return false;
        }
        int slot = b.find(Material.ENDER_PEARL);
        if (slot < 0) {
            return false;
        }
        boolean escape = on(b, "pearl.escape") && m.getHealth() <= num(b, "pearl.escape-health") && dist < 10;
        boolean close = dist > num(b, "pearl.min-distance");
        if (!escape && !close) {
            return false;
        }
        Location eye = m.getEyeLocation();
        Vector dir;
        if (escape) {
            Vector away = eye.toVector().subtract(t.getLocation().toVector()).setY(0);
            if (away.lengthSquared() < 1e-6) {
                away = new Vector(1, 0, 0);
            }
            dir = away.normalize().setY(0.6);
        } else {
            Vector to = t.getLocation().toVector().subtract(eye.toVector());
            double d = to.length();
            dir = to.normalize();
            dir.setY(dir.getY() + Math.min(0.5, d * 0.012));
        }
        dir.normalize().multiply(1.5);
        mgr.face(m, eye.clone().add(dir.clone().multiply(5)));
        m.launchProjectile(EnderPearl.class, dir);
        m.swingMainHand();
        if (on(b, "items.consume")) {
            b.take(slot, 1);
        }
        b.st.setCd("pearl", intv(b, "pearl.cooldown"));
        return true;
    }
}
