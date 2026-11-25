package com.example.fakeplayers;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
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
import org.bukkit.entity.minecart.ExplosiveMinecart;
import org.bukkit.inventory.EquipmentSlot;
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
        if (st.pcrit > 0) {
            st.pcrit--;
        }
        if (st.stunWindow > 0) {
            st.stunWindow--;
        }
        if (st.noSelect > 0) {
            st.noSelect--;
        }
        trackMotion(b, m);

        waterClutch(b, m);
        ensureTotem(b);
        handleEating(b, m);
        restoreChestIfLanded(b, m);
        escapeWeb(b, m);

        LivingEntity t = resolveTarget(b, m);
        if (t == null) {
            st.shielding = false;
            setShieldVisual(b, m, false);
            st.drawTicks = 0;
            st.turtleTicks = 0;
            st.prefightDone = false;
            st.prefighting = false;
            endFlight(b, m);
            boolean busy = on(b, "invis.rewear") && rewear(b);
            return st.eatTicks > 0 || busy;
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
            if (st.turtleTicks > 0 || st.prefighting) {
                // standing ground: turtle healing or potting up before the fight
                aimAt(b, m, aimPoint(t));
                return true;
            }
            if (center < 7.0) {
                backOff(b, m, t);
            } else {
                engage(b, m, t, 0.4);
            }
            aimAt(b, m, aimPoint(t));
            return true;
        }

        if (turtle(b, m, t, center)) {
            return true;
        }
        if (heal(b, m)) {
            return true;
        }
        if (prefight(b, m, center) || invis(b, m, center)) {
            return true;
        }
        buff(b, m, center);

        if (elytra(b, m, t, center)) {
            melee(b, m, t, dist);
            return true;
        }
        if (pearlTech(b, m, t, center)) {
            return true;
        }
        if (pearl(b, m, t, center)) {
            return true;
        }
        if (!web(b, m, t, center) && !lava(b, m, t, center) && !cart(b, m, t, center)
                && !crystal(b, m, t, center)) {
            anchor(b, m, t, center);
        }
        if (!spearmace(b, m, t, center) && !lungeSwap(b, m, t, center)) {
            mace(b, m, t, center);
        }

        if (ranged(b, m, t, center)) {
            engage(b, m, t, 0.8);
            return true;
        }

        bridge(b, m, t);
        selectWeapon(b, m, t);
        aimAt(b, m, aimPoint(t));
        engage(b, m, t, 1.0);
        melee(b, m, t, dist);
        return true;
    }

    /** Teleport detection (pearl crit window) and highest point of the current jump or flight. */
    private void trackMotion(Bot b, Mannequin m) {
        Bot.CombatState st = b.st;
        Location l = m.getLocation();
        if (st.lastLoc != null && st.lastLoc.getWorld() != null && st.lastLoc.getWorld().equals(l.getWorld())
                && st.lastLoc.distanceSquared(l) > 36.0) {
            st.pcrit = 0;
        }
        st.vy = st.lastLoc != null && st.lastLoc.getWorld() != null && st.lastLoc.getWorld().equals(l.getWorld())
                ? l.getY() - st.lastLoc.getY() : 0;
        st.lastLoc = l.clone();
        if (m.isOnGround()) {
            st.peakY = l.getY();
        } else {
            st.peakY = Math.max(st.peakY, l.getY());
        }
    }

    private double effectiveFall(Bot b, Mannequin m) {
        if (m.isOnGround()) {
            return 0;
        }
        return Math.min(60.0, Math.max(m.getFallDistance(), b.st.peakY - m.getLocation().getY()));
    }

    // ------------------------------------------------------------ targeting

    private LivingEntity resolveTarget(Bot b, Mannequin m) {
        Bot.CombatState st = b.st;

        if (b.clickTarget != null) {
            Entity ce = Bukkit.getEntity(b.clickTarget);
            if (ce instanceof LivingEntity cl && !cl.isDead() && cl.getWorld().equals(m.getWorld())) {
                if (!cl.getUniqueId().equals(st.targetId)) {
                    st.targetId = cl.getUniqueId();
                    st.reaction = Math.max(0, intv(b, "aim.reaction-ticks"));
                }
                return cl;
            }
            st.targetId = null;
            return null;
        }

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
        if (b.immovable || st.reaction > 0 || st.flight != 0) {
            return;
        }
        Location ml = m.getLocation();
        Location goal = t.getLocation();
        double dx = goal.getX() - ml.getX();
        double dz = goal.getZ() - ml.getZ();
        double h = Math.max(1e-4, Math.hypot(dx, dz));
        double ux = dx / h;
        double uz = dz / h;
        double px = -uz;
        double pz = ux;

        double min = num(b, "move.min-distance");
        double max = num(b, "move.max-distance");
        double f;
        f = h > max ? 1.0 : (h < min ? -0.5 : 0.4);

        // light strafing: only up close, only while the attack recharges, and it flips rarely
        String mode = str(b, "move.strafe");
        double s = 0;
        if (!"none".equals(mode) && h < 5.0 && st.attackTimer > 3) {
            if (--st.strafeTimer <= 0) {
                int interval = Math.max(10, intv(b, "move.strafe-interval"));
                st.strafeTimer = interval + ThreadLocalRandom.current().nextInt(interval);
                double flip = "random".equals(mode) ? 0.5 : 0.25;
                if (rnd() < flip) {
                    st.strafeDir = -st.strafeDir;
                }
            }
            s = st.strafeDir * num(b, "move.strafe-amount");
        }

        boolean retreat = on(b, "move.retreat") && m.getHealth() <= num(b, "move.retreat-health") && st.turtleTicks <= 0;
        if (retreat) {
            f = -1.0;
            s *= 0.5;
        }

        double vx = ux * f + px * s;
        double vz = uz * f + pz * s;
        double len = Math.hypot(vx, vz);
        boolean moving = len > 0.05;
        if (len > 1e-4) {
            vx /= len;
            vz /= len;
        } else {
            vx = 0;
            vz = 0;
        }
        boolean sprint = f > 0 && h > 3.0;
        double speed = (sprint ? num(b, "move.sprint-speed") : num(b, "move.speed")) * speedMul;
        mgr.walkSmooth(b, m, vx, vz, speed, moving);

        // jump for a critical hit just before the next swing (or right after a pearl teleport)
        boolean wantJump = false;
        if (st.pcrit > 0) {
            wantJump = false;
        } else if (on(b, "move.jump-crit") && m.isOnGround() && h < 4.5 && !retreat
                && st.attackTimer <= 2 && rnd() < num(b, "move.jump-chance") * 0.35) {
            wantJump = true;
        }
        if (wantJump && st.jumpedTicks == 0) {
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
        if (b.st.eatTicks > 0 || b.st.noSelect > 0) {
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

    private static boolean isMelee(ItemStack it) {
        if (Bot.empty(it)) {
            return false;
        }
        Material mt = it.getType();
        String n = mt.name();
        return n.endsWith("_SWORD") || n.endsWith("_AXE") || n.endsWith("_SPEAR")
                || mt == Material.MACE || mt == Material.TRIDENT;
    }

    private int fastestCooldown(Bot b) {
        int best = Integer.MAX_VALUE;
        for (int i = 0; i < 9; i++) {
            if (isMelee(b.inv[i])) {
                best = Math.min(best, cooldownTicks(b, b.inv[i]));
            }
        }
        return best == Integer.MAX_VALUE ? cooldownTicks(b, b.held()) : best;
    }

    /** Punish crit: the bot was just hit and knocked up, so it crits back while it falls. */
    public void onHurt(Bot b, Mannequin m, Entity attacker) {
        if (!on(b, "pcrit.enabled") || attacker == m) {
            return;
        }
        b.st.pcrit = Math.max(1, intv(b, "pcrit.window"));
        b.st.attackTimer = 0;
        if (b.st.targetId == null && attacker instanceof LivingEntity) {
            b.st.targetId = attacker.getUniqueId();
        }
    }

    /**
     * Stun slam: falling onto a blocking player, the axe disables the shield and the mace smashes in the same tick.
     */
    private boolean stunSlam(Bot b, Mannequin m, LivingEntity t, double dist) {
        Bot.CombatState st = b.st;
        if (!on(b, "stunslam.enabled") || m.isOnGround() || !st.ready("stunslam") || dist > num(b, "hit.reach")
                || !(t instanceof Player sp) || !sp.isBlocking() || effectiveFall(b, m) < 1.5) {
            return false;
        }
        Vector look = sp.getEyeLocation().getDirection().setY(0);
        Vector to = m.getLocation().toVector().subtract(sp.getLocation().toVector()).setY(0);
        if (look.lengthSquared() < 1e-6 || to.lengthSquared() < 1e-6 || look.normalize().dot(to.normalize()) <= 0) {
            return false;
        }
        int axe = findWeapon(b, "AXE");
        int mace = findWeapon(b, "MACE");
        if (axe < 0 || mace < 0) {
            return false;
        }
        // 1) axe: disable the shield
        b.selected = axe;
        sp.setCooldown(Material.SHIELD, intv(b, "shield.axe-disable-ticks"));
        sp.clearActiveItem();
        m.swingMainHand();
        t.getWorld().playSound(t.getLocation(), Sound.ITEM_SHIELD_BREAK, 1f, 1f);
        // 2) mace: smash straight away, same tick
        b.selected = mace;
        b.equipDirty = true;
        st.noSelect = Math.max(st.noSelect, 4);
        st.attackTimer = cooldownTicks(b, b.inv[mace]);
        st.setCd("stunslam", 10);
        hit(b, m, t);
        return true;
    }

    private void melee(Bot b, Mannequin m, LivingEntity t, double dist) {
        Bot.CombatState st = b.st;
        if (stunSlam(b, m, t, dist)) {
            return;
        }
        if (dist > num(b, "hit.reach") || st.shielding || st.attackTimer > 0) {
            return;
        }
        if (!facing(m, aimPoint(t), 30)) {
            return;
        }
        // punish crit: knocked into the air, wait until falling, then hit back
        if (st.pcrit > 0 && !m.isOnGround() && st.vy > -0.04) {
            return;
        }
        // after a jump, wait until the bot is falling so the hit is a critical
        if (on(b, "hit.crit") && st.jumpedTicks > 0 && !m.isOnGround() && st.vy > -0.04) {
            return;
        }
        if (b.platform == Bot.Platform.BEDROCK) {
            double cps = Math.max(1.0, num(b, "hit.cps"));
            st.attackTimer = Math.max(1, (int) Math.round(20.0 / cps) + ThreadLocalRandom.current().nextInt(-1, 2));
        } else if (on(b, "swap.attribute")) {
            st.attackTimer = fastestCooldown(b);
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

    /** With a Breach mace the target's armor counts for less; scale the damage to match. */
    private static double breachScale(ItemStack w, LivingEntity t) {
        int lvl = w.getEnchantmentLevel(Enchantment.BREACH);
        if (lvl <= 0) {
            return 1.0;
        }
        double frac = Math.min(20.0, attr(t, Attribute.ARMOR)) / 25.0;
        if (frac >= 0.99) {
            return 1.0;
        }
        double left = frac * Math.max(0.0, 1.0 - 0.15 * lvl);
        return (1.0 - left) / (1.0 - frac);
    }

    /** Expected value of hitting right now with the item in this hotbar slot. */
    private double hitScore(Bot b, Mannequin m, LivingEntity t, int slot) {
        ItemStack it = b.inv[slot];
        if (!isMelee(it)) {
            return -1;
        }
        double d = weaponDamage(it);
        Material mt = it.getType();
        double fall = effectiveFall(b, m);
        if (mt == Material.MACE) {
            if (fall > 1.5) {
                d += maceBonus(fall) + 0.5 * it.getEnchantmentLevel(Enchantment.DENSITY) * fall;
                if (on(b, "swap.breach")) {
                    d *= breachScale(it, t);
                }
            }
        } else if (isAxe(mt) && t instanceof Player p && p.isBlocking()) {
            d += 6.0;
        }
        return d;
    }

    /** Attribute swap: the slot to hit with (the best item for this moment), which may differ from the held one. */
    private int pickHitSlot(Bot b, Mannequin m, LivingEntity t) {
        if (!on(b, "swap.attribute")) {
            return b.selected;
        }
        int best = b.selected;
        double bestScore = hitScore(b, m, t, best);
        for (int i = 0; i < 9; i++) {
            double sc = hitScore(b, m, t, i);
            if (sc > bestScore + 0.01) {
                bestScore = sc;
                best = i;
            }
        }
        return best;
    }

    private void hit(Bot b, Mannequin m, LivingEntity t) {
        Bot.CombatState st = b.st;
        int slot = pickHitSlot(b, m, t);
        if (slot != b.selected) {
            b.selected = slot;
            b.equipDirty = true;
        }
        st.noSelect = Math.max(st.noSelect, 3);
        ItemStack w = b.inv[slot];
        Material wt = Bot.empty(w) ? Material.AIR : w.getType();
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

        double fall = effectiveFall(b, m);
        if (t instanceof Player sp && sp.isBlocking() && !isAxe(wt)) {
            Vector look = sp.getEyeLocation().getDirection().setY(0);
            Vector to = m.getLocation().toVector().subtract(sp.getLocation().toVector()).setY(0);
            if (look.lengthSquared() > 1e-6 && to.lengthSquared() > 1e-6 && look.normalize().dot(to.normalize()) > 0) {
                // blocked by the shield: no damage, no smash, no wind burst
                m.swingMainHand();
                t.getWorld().playSound(t.getLocation(), Sound.ITEM_SHIELD_BLOCK, 1f, 1f);
                return;
            }
        }
        boolean smash = wt == Material.MACE && fall > 1.5 && !m.isOnGround();
        boolean crit = on(b, "hit.crit") && !smash && !m.isOnGround() && st.vy < -0.04
                && (fall > 0 || st.pcrit > 0);
        if (crit) {
            dmg *= 1.5;
        }
        if (smash) {
            dmg += maceBonus(fall);
            int density = w.getEnchantmentLevel(Enchantment.DENSITY);
            if (density > 0) {
                dmg += 0.5 * density * fall;
            }
            if (on(b, "swap.breach")) {
                dmg *= breachScale(w, t);
            }
        }

        boolean brokeShield = false;
        if (t instanceof Player p && p.isBlocking() && isAxe(wt)) {
            p.setCooldown(Material.SHIELD, intv(b, "shield.axe-disable-ticks"));
            p.clearActiveItem();
            brokeShield = true;
        }

        m.swingMainHand();
        if (b.platform == Bot.Platform.BEDROCK && on(b, "bedrock.ignore-immunity")) {
            // Bedrock has no hit immunity: every click deals full damage, even right after a previous hit
            t.setNoDamageTicks(0);
        }
        t.damage(dmg, m);
        hitEffects(t, w, crit, smash, fall);

        if (brokeShield && on(b, "stunslam.enabled")) {
            st.stunWindow = 30;
            st.setCd("mace", 0);
        }

        double extra = num(b, "hit.knockback-extra");
        if (extra > 0) {
            Vector dir = t.getLocation().toVector().subtract(m.getLocation().toVector()).setY(0);
            if (dir.lengthSquared() > 1e-6) {
                t.setVelocity(t.getVelocity().add(dir.normalize().multiply(extra)));
            }
        }
        if (smash) {
            m.setFallDistance(0f);
            st.peakY = m.getLocation().getY();
            endFlight(b, m);
            int wind = w.getEnchantmentLevel(Enchantment.WIND_BURST);
            if (wind > 0) {
                Vector v = m.getVelocity();
                m.setVelocity(new Vector(v.getX(), 1.0 + 0.4 * wind, v.getZ()));
            }
        }
        String victim = t instanceof Player p ? p.getName() : t.getType().name();
        mgr.runCommands("integration.on-bot-hit", mgr.placeholders(b, m, "victim", victim));
    }

    /** Particles and sounds a real player's hit would show. */
    private void hitEffects(LivingEntity t, ItemStack w, boolean crit, boolean smash, double fall) {
        Location mid = t.getLocation().add(0, t.getHeight() * 0.5, 0);
        if (crit) {
            t.getWorld().spawnParticle(org.bukkit.Particle.CRIT, mid, 20, 0.3, 0.4, 0.3, 0.25);
            t.getWorld().playSound(mid, Sound.ENTITY_PLAYER_ATTACK_CRIT, 1f, 1f);
        } else if (!smash) {
            t.getWorld().playSound(mid, Sound.ENTITY_PLAYER_ATTACK_STRONG, 1f, 1f);
        }
        if (!Bot.empty(w) && !w.getEnchantments().isEmpty()) {
            t.getWorld().spawnParticle(org.bukkit.Particle.ENCHANTED_HIT, mid, 12, 0.3, 0.4, 0.3, 0.3);
        }
        if (smash) {
            org.bukkit.block.data.BlockData bd = t.getLocation().subtract(0, 1, 0).getBlock().getBlockData();
            if (bd.getMaterial().isAir()) {
                bd = Material.DIRT.createBlockData();
            }
            t.getWorld().spawnParticle(org.bukkit.Particle.DUST_PILLAR, t.getLocation(), 30, 0.6, 0.1, 0.6, 0.15, bd);
            Sound snd = fall > 5 ? Sound.ITEM_MACE_SMASH_GROUND_HEAVY
                    : (t.isOnGround() ? Sound.ITEM_MACE_SMASH_GROUND : Sound.ITEM_MACE_SMASH_AIR);
            t.getWorld().playSound(t.getLocation(), snd, 1f, 1f);
        }
    }

    // ------------------------------------------------------------ shield (emulated)

    private static boolean isShield(ItemStack i) {
        return !Bot.empty(i) && i.getType() == Material.SHIELD;
    }

    private void updateShield(Bot b, Mannequin m, double dist) {
        updateShieldLogic(b, dist);
        setShieldVisual(b, m, b.st.shielding);
    }

    /** Raises or lowers the shield animation (uses the server's item-use API when this Paper build has it). */
    private void setShieldVisual(Bot b, Mannequin m, boolean up) {
        Bot.CombatState st = b.st;
        if (!on(b, "shield.visual") || st.shieldRaised == up) {
            return;
        }
        st.shieldRaised = up;
        try {
            if (up) {
                EquipmentSlot hand = isShield(b.offhand) ? EquipmentSlot.OFF_HAND : EquipmentSlot.HAND;
                LivingEntity.class.getMethod("startUsingItem", EquipmentSlot.class).invoke(m, hand);
            } else {
                LivingEntity.class.getMethod("clearActiveItem").invoke(m);
            }
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // this Paper build has no item-use API; blocking still works, only the animation is missing
        }
    }

    private void updateShieldLogic(Bot b, double dist) {
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
        Bot.CombatState st = b.st;
        if (!on(b, "totem.refill") || !Bot.empty(b.offhand)) {
            st.totemWait = 0;
            return;
        }
        int s = b.find(Material.TOTEM_OF_UNDYING);
        if (s < 0) {
            st.totemWait = 0;
            return;
        }
        if (st.totemWait == 0) {
            int rand = Math.max(0, intv(b, "totem.delay-random"));
            st.totemWait = Math.max(1, intv(b, "totem.delay") + (rand > 0 ? ThreadLocalRandom.current().nextInt(rand + 1) : 0));
        }
        // like a player with the inventory open: no attacking while the totem is being moved
        st.attackTimer = Math.max(st.attackTimer, 2);
        if (--st.totemWait > 0) {
            return;
        }
        {
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

    private boolean hasHealItem(Bot b) {
        return b.find(Material.ENCHANTED_GOLDEN_APPLE) >= 0 || b.find(Material.GOLDEN_APPLE) >= 0
                || b.findIf(i -> (i.getType() == Material.POTION || i.getType() == Material.SPLASH_POTION)
                && isHealingPotion(i), false) >= 0;
    }

    /** Throws an ender pearl directly away from the target. */
    private boolean escapePearl(Bot b, Mannequin m, LivingEntity t) {
        if (!b.st.ready("pearl")) {
            return false;
        }
        int slot = b.find(Material.ENDER_PEARL);
        if (slot < 0) {
            return false;
        }
        Location eye = m.getEyeLocation();
        Vector away = eye.toVector().subtract(t.getLocation().toVector()).setY(0);
        if (away.lengthSquared() < 1e-6) {
            away = new Vector(1, 0, 0);
        }
        Vector dir = away.normalize().setY(0.6).normalize().multiply(1.5);
        mgr.face(m, eye.clone().add(dir.clone().multiply(5)));
        m.launchProjectile(EnderPearl.class, dir);
        m.swingMainHand();
        if (on(b, "items.consume")) {
            b.take(slot, 1);
        }
        b.st.setCd("pearl", intv(b, "pearl.cooldown"));
        return true;
    }

    /** Walks straight away from the target (used while eating with an enemy close by). */
    private void backOff(Bot b, Mannequin m, LivingEntity t) {
        if (b.immovable) {
            return;
        }
        Location ml = m.getLocation();
        Location tl = t.getLocation();
        double dx = ml.getX() - tl.getX();
        double dz = ml.getZ() - tl.getZ();
        double h = Math.max(1e-4, Math.hypot(dx, dz));
        mgr.walkSmooth(b, m, dx / h, dz / h, num(b, "move.speed") * 0.5, true);
    }

    /** Stuck in a cobweb: pour water on it, or cut free after a while. */
    private void escapeWeb(Bot b, Mannequin m) {
        Bot.CombatState st = b.st;
        Block feet = m.getLocation().getBlock();
        Block eye = m.getEyeLocation().getBlock();
        boolean stuck = feet.getType() == Material.COBWEB || eye.getType() == Material.COBWEB;
        if (!stuck) {
            st.webTicks = 0;
            return;
        }
        st.webTicks++;
        if (on(b, "water.web-escape") && st.webTicks >= 4 && st.ready("webwater")) {
            int slot = b.find(Material.WATER_BUCKET);
            if (slot >= 0) {
                Block target = feet.getType() == Material.COBWEB ? feet : eye;
                if (mgr.placeTemp(b, target, Material.WATER, intv(b, "water.pickup-ticks"), Material.BUCKET, Material.WATER_BUCKET)) {
                    Location l = m.getLocation();
                    m.setRotation(l.getYaw(), 90f);
                    m.swingMainHand();
                    if (on(b, "items.consume")) {
                        b.convertOne(slot, Material.BUCKET);
                    }
                    st.setCd("webwater", 40);
                    st.webTicks = 0;
                    return;
                }
            }
        }
        int cut = intv(b, "web.cut-ticks");
        if (cut > 0 && st.webTicks >= cut) {
            if (feet.getType() == Material.COBWEB) {
                feet.setType(Material.AIR);
            }
            if (eye.getType() == Material.COBWEB) {
                eye.setType(Material.AIR);
            }
            m.swingMainHand();
            st.webTicks = 0;
        }
    }

    // ------------------------------------------------------------ turtle master, pre-fight potions, invisibility

    private static boolean isTurtle(ItemStack i) {
        if (!Bot.empty(i) && i.getItemMeta() instanceof PotionMeta pm) {
            PotionType base = pm.getBasePotionType();
            return base != null && base.name().contains("TURTLE_MASTER");
        }
        return false;
    }

    private static boolean isInvisPotion(ItemStack i) {
        for (PotionEffect e : effectsOf(i)) {
            if (e.getType() == PotionEffectType.INVISIBILITY) {
                return true;
            }
        }
        return false;
    }

    private static PotionEffectType effectByName(String n) {
        return switch (n.trim().toLowerCase(java.util.Locale.ROOT)) {
            case "speed" -> PotionEffectType.SPEED;
            case "strength" -> PotionEffectType.STRENGTH;
            case "fire_resistance", "fire" -> PotionEffectType.FIRE_RESISTANCE;
            case "resistance" -> PotionEffectType.RESISTANCE;
            case "regeneration" -> PotionEffectType.REGENERATION;
            case "jump_boost" -> PotionEffectType.JUMP_BOOST;
            case "invisibility" -> PotionEffectType.INVISIBILITY;
            case "night_vision" -> PotionEffectType.NIGHT_VISION;
            case "water_breathing" -> PotionEffectType.WATER_BREATHING;
            case "slow_falling" -> PotionEffectType.SLOW_FALLING;
            default -> null;
        };
    }

    /** Low on health: drink a Turtle Master, heal a bit while it protects, then go back to fighting. */
    private boolean turtle(Bot b, Mannequin m, LivingEntity t, double center) {
        Bot.CombatState st = b.st;
        if (!on(b, "turtle.enabled")) {
            st.turtleTicks = 0;
            return false;
        }
        double hp = m.getHealth();
        if (st.turtleTicks > 0) {
            st.turtleTicks--;
            if (hp < num(b, "turtle.heal-to") && healNow(b, m)) {
                return true;
            }
            if (hp >= num(b, "turtle.heal-to") || !hasHealItem(b)) {
                st.turtleTicks = 0;
            }
            return false;
        }
        if (hp > num(b, "turtle.health") || !st.ready("turtle")) {
            return false;
        }
        int s = b.findIf(i -> i.getType() == Material.POTION && isTurtle(i), false);
        if (s >= 0) {
            st.setCd("turtle", intv(b, "turtle.cooldown"));
            st.turtleTicks = 400;
            startEat(b, s, "potion");
            return true;
        }
        s = b.findIf(i -> i.getType() == Material.SPLASH_POTION && isTurtle(i), false);
        if (s >= 0) {
            st.setCd("turtle", intv(b, "turtle.cooldown"));
            st.turtleTicks = 400;
            throwSplashAtFeet(b, m, s);
            return true;
        }
        return false;
    }

    /** Heals right now with the best thing in the inventory, whatever the thresholds. */
    private boolean healNow(Bot b, Mannequin m) {
        int s = b.find(Material.ENCHANTED_GOLDEN_APPLE);
        if (s >= 0) {
            startEat(b, s, "egap");
            return true;
        }
        s = b.find(Material.GOLDEN_APPLE);
        if (s >= 0) {
            startEat(b, s, "gap");
            return true;
        }
        if (b.st.ready("splash")) {
            s = b.findIf(i -> i.getType() == Material.SPLASH_POTION && isHealingPotion(i), false);
            if (s >= 0) {
                throwSplashAtFeet(b, m, s);
                return true;
            }
        }
        s = b.findIf(i -> i.getType() == Material.POTION && isHealingPotion(i), false);
        if (s >= 0) {
            startEat(b, s, "potion");
            return true;
        }
        return false;
    }

    /** Pot up (speed, strength, fire resistance...) while the enemy is still far away. */
    private boolean prefight(Bot b, Mannequin m, double center) {
        Bot.CombatState st = b.st;
        st.prefighting = false;
        if (!on(b, "buff.prefight") || st.prefightDone) {
            return false;
        }
        if (center < num(b, "buff.prefight-min-distance")) {
            st.prefightDone = true;
            return false;
        }
        List<PotionEffectType> want = new ArrayList<>();
        for (String n : str(b, "buff.prefight-effects").split(",")) {
            PotionEffectType pt = effectByName(n);
            if (pt != null && !m.hasPotionEffect(pt)) {
                want.add(pt);
            }
        }
        int s = b.findIf(i -> {
            if (i.getType() != Material.POTION || isHealingPotion(i) || isTurtle(i)) {
                return false;
            }
            for (PotionEffect e : effectsOf(i)) {
                if (want.contains(e.getType())) {
                    return true;
                }
            }
            return false;
        }, false);
        if (s < 0) {
            st.prefightDone = true;
            return false;
        }
        st.prefighting = true;
        startEat(b, s, "potion");
        return true;
    }

    /** Drinks invisibility, then takes the armor off (it would show). Armor goes back on when it runs low. */
    private boolean invis(Bot b, Mannequin m, double center) {
        Bot.CombatState st = b.st;
        if (!on(b, "invis.enabled")) {
            return false;
        }
        PotionEffect ef = m.getPotionEffect(PotionEffectType.INVISIBILITY);
        boolean has = ef != null && ef.getDuration() > intv(b, "invis.rewear-ticks");
        if (!has) {
            if (st.strippedArmor != null && on(b, "invis.rewear")) {
                return rewear(b);
            }
            if (ef == null && st.strippedArmor == null && st.ready("invis")
                    && center <= num(b, "invis.range") && center >= num(b, "invis.min-distance")) {
                int s = b.findIf(i -> i.getType() == Material.POTION && isInvisPotion(i), false);
                if (s >= 0) {
                    st.setCd("invis", 600);
                    st.prefighting = true;
                    startEat(b, s, "potion");
                    return true;
                }
            }
            return false;
        }
        if (!on(b, "invis.strip-armor") || center < 6.0) {
            return false;
        }
        if (st.stripTimer > 0) {
            st.stripTimer--;
            return true;
        }
        for (int idx = 0; idx < 4; idx++) {
            ItemStack piece = b.armor[idx];
            if (Bot.empty(piece)) {
                continue;
            }
            ItemStack rest = b.give(piece.clone());
            if (rest != null) {
                dropLeastUseful(b, m);
                rest = b.give(rest);
                if (rest != null) {
                    return false;
                }
            }
            if (st.strippedArmor == null) {
                st.strippedArmor = new ItemStack[4];
            }
            st.strippedArmor[idx] = piece;
            b.armor[idx] = null;
            b.equipDirty = true;
            st.stripTimer = Math.max(1, intv(b, "invis.strip-delay"));
            m.getWorld().playSound(m.getLocation(), Sound.ITEM_ARMOR_EQUIP_GENERIC, 0.8f, 1f);
            return true;
        }
        return false;
    }

    /** Puts stripped armor back on, one piece at a time. Returns true while still busy. */
    private boolean rewear(Bot b) {
        Bot.CombatState st = b.st;
        if (st.strippedArmor == null) {
            return false;
        }
        if (st.stripTimer > 0) {
            st.stripTimer--;
            return true;
        }
        for (int idx = 0; idx < 4; idx++) {
            ItemStack want = st.strippedArmor[idx];
            if (want == null) {
                continue;
            }
            st.strippedArmor[idx] = null;
            int slot = b.findIf(i -> i.isSimilar(want), false);
            if (slot >= 0 && Bot.empty(b.armor[idx])) {
                b.inv[slot] = null;
                b.armor[idx] = want;
                b.equipDirty = true;
                st.stripTimer = Math.max(1, intv(b, "invis.strip-delay"));
                return true;
            }
        }
        st.strippedArmor = null;
        return false;
    }

    private static int keepScore(ItemStack it) {
        Material mt = it.getType();
        String n = mt.name();
        if (mt == Material.TOTEM_OF_UNDYING) {
            return 100;
        }
        if (mt == Material.ENCHANTED_GOLDEN_APPLE || mt == Material.GOLDEN_APPLE) {
            return 95;
        }
        if (n.endsWith("_SWORD") || n.endsWith("_AXE") || n.endsWith("_SPEAR") || mt == Material.MACE
                || mt == Material.TRIDENT) {
            return 90;
        }
        if (mt == Material.POTION || mt == Material.SPLASH_POTION || mt == Material.SHIELD) {
            return 85;
        }
        if (mt == Material.ENDER_PEARL || mt == Material.WIND_CHARGE || mt == Material.ELYTRA) {
            return 80;
        }
        if (mt == Material.WATER_BUCKET || mt == Material.LAVA_BUCKET || mt == Material.COBWEB) {
            return 75;
        }
        if (n.contains("BOW") || n.contains("ARROW") || mt == Material.FIREWORK_ROCKET) {
            return 70;
        }
        if (mt == Material.END_CRYSTAL || mt == Material.RESPAWN_ANCHOR || mt == Material.OBSIDIAN
                || mt == Material.TNT_MINECART || mt == Material.RAIL) {
            return 65;
        }
        if (n.endsWith("_HELMET") || n.endsWith("_CHESTPLATE") || n.endsWith("_LEGGINGS") || n.endsWith("_BOOTS")) {
            return 55;
        }
        if (mt.isEdible()) {
            return 30;
        }
        if (mt.isBlock()) {
            return 25;
        }
        return 10;
    }

    /** Throws the least useful stack out of the inventory to make room. */
    private void dropLeastUseful(Bot b, Mannequin m) {
        int worst = -1;
        long worstScore = Long.MAX_VALUE;
        for (int i = 0; i < 36; i++) {
            if (Bot.empty(b.inv[i])) {
                continue;
            }
            long sc = keepScore(b.inv[i]) * 1000L - b.inv[i].getAmount();
            if (sc < worstScore) {
                worstScore = sc;
                worst = i;
            }
        }
        if (worst < 0) {
            return;
        }
        ItemStack out = b.inv[worst];
        b.inv[worst] = null;
        b.equipDirty = true;
        org.bukkit.entity.Item drop = m.getWorld().dropItem(m.getEyeLocation(), out);
        drop.setVelocity(m.getEyeLocation().getDirection().multiply(0.25));
        drop.setPickupDelay(40);
        drop.setThrower(m.getUniqueId());
        m.swingMainHand();
    }

    private boolean heal(Bot b, Mannequin m) {
        if (!on(b, "heal.enabled") || b.st.eatTicks > 0) {
            return false;
        }
        double hp = m.getHealth();
        if (on(b, "pearl.heal") && b.st.turtleTicks <= 0
                && (hp <= num(b, "heal.gap-health") || hp <= num(b, "heal.potion-health"))
                && hasHealItem(b)) {
            LivingEntity enemy = currentTarget(b);
            if (enemy != null && reachDist(m, enemy) < 8.0 && escapePearl(b, m, enemy)) {
                return true;
            }
        }
        if (hp <= num(b, "heal.egap-health")) {
            int s = b.find(Material.ENCHANTED_GOLDEN_APPLE);
            if (s >= 0) {
                startEat(b, s, "egap");
                return true;
            }
        }
        if (hp <= num(b, "heal.potion-health")) {
            if (on(b, "heal.splash") && b.st.ready("splash")) {
                int s = b.findIf(i -> i.getType() == Material.SPLASH_POTION && isHealingPotion(i), false);
                if (s >= 0) {
                    throwSplashAtFeet(b, m, s);
                    return true;
                }
            }
            int s = b.findIf(i -> i.getType() == Material.POTION && isHealingPotion(i), false);
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
        int s = b.findIf(i -> {
            if (i.getType() != Material.POTION || isHealingPotion(i) || isTurtle(i) || isInvisPotion(i)) {
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
        b.st.setCd("lava", Math.max(0, intv(b, "lava.combo-delay")));
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
        if (!mgr.placeTemp(b, tb, Material.LAVA, intv(b, "lava.pickup-ticks"), from, to)) {
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
        if (mgr.placeTemp(b, above, Material.WATER, intv(b, "water.pickup-ticks"), Material.BUCKET, Material.WATER_BUCKET)) {
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

        boolean stun = on(b, "stunslam.enabled") && b.st.stunWindow > 0;
        if ((!stun && !b.st.ready("mace")) || dist > num(b, "mace.launch-range") || dist < 2.0 || b.immovable) {
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
            arrowSlot = b.findIf(i -> i.getType() == Material.ARROW || i.getType() == Material.SPECTRAL_ARROW
                    || i.getType() == Material.TIPPED_ARROW, false);
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

    // ------------------------------------------------------------ spear + mace, elytra, carts, grapple, bridging

    private int enchantLevel(ItemStack it, String key) {
        Enchantment e = Registry.ENCHANTMENT.get(NamespacedKey.minecraft(key));
        return e == null ? 0 : it.getEnchantmentLevel(e);
    }

    /** Lunge with a spear to fly at the target, then swap to the mace for the smash. */
    /** Hotbar slot of the fast item the bot waits with: an empty slot (bare hand) or a quick weapon. */
    private int lungeFastSlot(Bot b, int spear) {
        String want = str(b, "lungeswap.fast-item");
        if ("hand".equals(want) || "auto".equals(want)) {
            for (int i = 0; i < 9; i++) {
                if (Bot.empty(b.inv[i])) {
                    return i;
                }
            }
            if ("hand".equals(want)) {
                return -1;
            }
        }
        int best = -1;
        int bestCd = Integer.MAX_VALUE;
        for (int i = 0; i < 9; i++) {
            if (i == spear || !isMelee(b.inv[i]) || b.inv[i].getType() == Material.MACE) {
                continue;
            }
            String n = b.inv[i].getType().name();
            if ("sword".equals(want) && !n.endsWith("_SWORD") || "axe".equals(want) && !n.endsWith("_AXE")) {
                continue;
            }
            int cd = cooldownTicks(b, b.inv[i]);
            if (cd < bestCd) {
                bestCd = cd;
                best = i;
            }
        }
        return best;
    }

    /**
     * Lunge swapping (attribute swap): wait holding a fast item until its attack cooldown is full, then switch to
     * the Lunge spear and jab in the same tick, so the lunge uses the fast item's cooldown. The bot swaps straight
     * back and repeats at the fast item's rate.
     */
    private boolean lungeSwap(Bot b, Mannequin m, LivingEntity t, double dist) {
        Bot.CombatState st = b.st;
        if (!on(b, "lungeswap.enabled") || b.immovable || st.shielding || st.eatTicks > 0) {
            return false;
        }
        double lMax = m.isOnGround() ? num(b, "lungeswap.max-range") : num(b, "lungeswap.air-range");
        if (dist < num(b, "lungeswap.min-range") || dist > lMax) {
            return false;
        }
        int spear = findWeapon(b, "SPEAR");
        if (spear < 0) {
            return false;
        }
        int fast = lungeFastSlot(b, spear);
        if (fast < 0) {
            return false;
        }
        // hold the fast item while the cooldown refills
        st.noSelect = Math.max(st.noSelect, 2);
        if (b.selected != fast) {
            b.selected = fast;
            b.equipDirty = true;
        }
        if (!st.ready("lungeswap")) {
            return false;
        }
        Vector dir = aimPoint(t).toVector().subtract(m.getEyeLocation().toVector());
        if (dir.lengthSquared() < 1e-6) {
            return false;
        }
        // same tick: swap to the spear and jab
        b.selected = spear;
        b.equipDirty = true;
        int lunge = enchantLevel(b.inv[spear], "lunge");
        dir.normalize();
        Vector v = dir.multiply(0.6 + 0.2 * Math.max(1, lunge));
        if (m.isOnGround()) {
            v.setY(Math.max(v.getY(), 0.0) + 0.1);
        }
        mgr.face(m, aimPoint(t));
        m.setVelocity(v);
        m.swingMainHand();
        m.getWorld().playSound(m.getLocation(), Sound.ITEM_TRIDENT_RIPTIDE_1, 0.6f, 1.4f);
        int cd = intv(b, "lungeswap.override-cooldown");
        if (cd <= 0) {
            cd = cooldownTicks(b, b.inv[fast]);
        }
        st.setCd("lungeswap", Math.max(2, cd));
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            b.selected = fast;
            b.equipDirty = true;
        }, 1L);
        return true;
    }

    private boolean spearmace(Bot b, Mannequin m, LivingEntity t, double dist) {
        Bot.CombatState st = b.st;
        if (!on(b, "spearmace.enabled") || !st.ready("spearmace") || b.immovable) {
            st.smArming = false;
            return false;
        }
        double maxR = m.isOnGround() ? num(b, "spearmace.max-range") : num(b, "spearmace.air-range");
        if (dist < num(b, "spearmace.min-range") || dist > maxR) {
            st.smArming = false;
            return false;
        }
        int spear = findWeapon(b, "SPEAR");
        int mace = findWeapon(b, "MACE");
        if (spear < 0 || mace < 0) {
            return false;
        }
        // lunge swapping: wait with a fast item until its cooldown is full, then swap to the spear in the same tick
        int fast = on(b, "spearmace.attribute-swap") ? lungeFastSlot(b, spear) : -1;
        if (fast >= 0) {
            if (!st.smArming) {
                st.smArming = true;
                st.setCd("smarm", cooldownTicks(b, b.inv[fast]));
            }
            st.noSelect = Math.max(st.noSelect, 2);
            if (b.selected != fast) {
                b.selected = fast;
                b.equipDirty = true;
            }
            if (!st.ready("smarm")) {
                return true;
            }
            st.smArming = false;
        }
        int lunge = enchantLevel(b.inv[spear], "lunge");
        if (fast >= 0) {
            b.selected = spear;
            b.equipDirty = true;
        } else {
            select(b, spear);
        }
        Vector dir = aimPoint(t).toVector().subtract(m.getEyeLocation().toVector());
        if (dir.lengthSquared() < 1e-6) {
            return false;
        }
        dir.normalize();
        Vector v = dir.multiply(0.9 + 0.25 * Math.max(1, lunge));
        v.setY(Math.max(v.getY(), 0.0) + 0.45);
        mgr.face(m, aimPoint(t));
        m.setVelocity(v);
        m.swingMainHand();
        long swapDelay = fast >= 0 ? Math.max(1, intv(b, "spearmace.swap-delay")) : 5L;
        st.noSelect = (int) swapDelay + 3;
        st.setCd("spearmace", dist > 12.0 ? intv(b, "spearmace.chain-cooldown") : intv(b, "spearmace.cooldown"));
        final int maceSlot = mace;
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            b.selected = maceSlot;
            b.equipDirty = true;
        }, swapDelay);
        return true;
    }

    /** Elytra macing: take off with a rocket, climb above the target, then dive bomb it with the mace. */
    private boolean elytra(Bot b, Mannequin m, LivingEntity t, double dist) {
        Bot.CombatState st = b.st;
        if (!on(b, "elytra.enabled")) {
            endFlight(b, m);
            return false;
        }
        Location ml = m.getLocation();
        Location tl = t.getLocation();

        if (st.flight == 0) {
            if (!m.isOnGround() || !st.ready("elytra") || b.immovable || dist < 8 || dist > 70) {
                return false;
            }
            int mace = findWeapon(b, "MACE");
            int rocket = b.find(Material.FIREWORK_ROCKET);
            if (mace < 0 || rocket < 0) {
                return false;
            }
            boolean wearing = !Bot.empty(b.armor[1]) && b.armor[1].getType() == Material.ELYTRA;
            if (!wearing) {
                int ely = b.find(Material.ELYTRA);
                if (ely < 0 || !on(b, "elytra.swap-chest")) {
                    return false;
                }
                ItemStack old = b.armor[1];
                b.armor[1] = b.inv[ely];
                b.inv[ely] = old;
                st.restoreChest = true;
                b.equipDirty = true;
            }
            if (on(b, "items.consume")) {
                b.take(rocket, 1);
            }
            m.setVelocity(new Vector(0, 0.8, 0));
            m.getWorld().playSound(ml, Sound.ENTITY_FIREWORK_ROCKET_LAUNCH, 1f, 1f);
            select(b, mace);
            st.flight = 1;
            st.flightTicks = 0;
            st.peakY = ml.getY();
            st.setCd("elytra", intv(b, "elytra.cooldown"));
            return true;
        }

        st.flightTicks++;
        st.noSelect = 6;
        if ((m.isOnGround() && st.flightTicks > 6) || st.flightTicks > 240) {
            endFlight(b, m);
            return false;
        }
        if (!m.isOnGround() && !m.isGliding()) {
            m.setGliding(true);
        }
        double dx = tl.getX() - ml.getX();
        double dz = tl.getZ() - ml.getZ();
        double h = Math.max(1e-4, Math.hypot(dx, dz));
        Vector dir;
        double speed;
        if (st.flight == 1) {
            if (ml.getY() - tl.getY() >= num(b, "elytra.height")) {
                st.flight = 2;
                st.peakY = Math.max(st.peakY, ml.getY());
            }
            dir = new Vector(dx / h * 0.35, 0.9, dz / h * 0.35);
            speed = 1.1;
            if (st.flightTicks % 25 == 10) {
                int rocket = b.find(Material.FIREWORK_ROCKET);
                if (rocket >= 0) {
                    if (on(b, "items.consume")) {
                        b.take(rocket, 1);
                    }
                    m.getWorld().playSound(ml, Sound.ENTITY_FIREWORK_ROCKET_LAUNCH, 1f, 1f);
                }
            }
        } else {
            dir = aimPoint(t).toVector().subtract(m.getEyeLocation().toVector());
            speed = 1.5;
        }
        if (dir.lengthSquared() < 1e-6) {
            return true;
        }
        dir.normalize();
        float yaw = (float) Math.toDegrees(Math.atan2(-dir.getX(), dir.getZ()));
        float pitch = (float) -Math.toDegrees(Math.asin(Math.max(-1.0, Math.min(1.0, dir.getY()))));
        m.setRotation(yaw, pitch);
        m.setVelocity(dir.multiply(speed));
        return true;
    }

    private void endFlight(Bot b, Mannequin m) {
        if (b.st.flight == 0) {
            return;
        }
        b.st.flight = 0;
        if (m.isGliding()) {
            m.setGliding(false);
        }
    }

    /** After an elytra flight, put the chestplate back on once the bot has landed. */
    private void restoreChestIfLanded(Bot b, Mannequin m) {
        Bot.CombatState st = b.st;
        if (!st.restoreChest || st.flight != 0 || !m.isOnGround()) {
            return;
        }
        if (!Bot.empty(b.armor[1]) && b.armor[1].getType() == Material.ELYTRA) {
            int plate = b.findIf(i -> i.getType().name().endsWith("_CHESTPLATE"), false);
            if (plate >= 0) {
                ItemStack old = b.armor[1];
                b.armor[1] = b.inv[plate];
                b.inv[plate] = old;
                b.equipDirty = true;
            }
        }
        st.restoreChest = false;
    }

    /** Cart PvP: rail + TNT minecart next to the target; "insta" blows at once, "xbow" after a crossbow shot. */
    private boolean cart(Bot b, Mannequin m, LivingEntity t, double dist) {
        Bot.CombatState st = b.st;
        if (!on(b, "cart.enabled") || !st.ready("cart") || dist > num(b, "cart.range")) {
            return false;
        }
        int rail = b.find(Material.RAIL);
        int tnt = b.find(Material.TNT_MINECART);
        if (rail < 0 || tnt < 0) {
            return false;
        }
        final boolean xbow = "xbow".equals(str(b, "cart.mode"));
        int bow = xbow ? findWeapon(b, "CROSSBOW") : -1;
        if (xbow && bow < 0) {
            return false;
        }
        Block tb = t.getLocation().getBlock();
        final double minT = num(b, "cart.min-target-damage");
        double maxS = Math.min(num(b, "cart.max-self-damage"), m.getHealth() - 1.0);
        Block spot = null;
        double bestScore = -1e9;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                Block c = tb.getRelative(dx, 0, dz);
                if (!replaceable(c) || c.getType() == Material.COBWEB
                        || !c.getRelative(BlockFace.DOWN).getType().isSolid()
                        || c.equals(m.getLocation().getBlock())) {
                    continue;
                }
                Location ctr = c.getLocation().add(0.5, 0.5, 0.5);
                double td = estimate(4, ctr, t);
                double sd = estimate(4, ctr, m);
                if (td < minT || sd > maxS) {
                    continue;
                }
                if (td - sd > bestScore) {
                    bestScore = td - sd;
                    spot = c;
                }
            }
        }
        if (spot == null || !mgr.placeTemp(b, spot, Material.RAIL, 60, null, null)) {
            return false;
        }
        ExplosiveMinecart cart = spot.getWorld().spawn(spot.getLocation().add(0.5, 0.1, 0.5), ExplosiveMinecart.class);
        if (on(b, "items.consume")) {
            b.take(rail, 1);
            b.take(tnt, 1);
        }
        if (xbow) {
            select(b, bow);
        }
        m.swingMainHand();
        st.noSelect = 12;
        st.setCd("cart", intv(b, "cart.cooldown"));
        final UUID id = cart.getUniqueId();
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            Entity e = Bukkit.getEntity(id);
            Mannequin mm = mgr.entity(b);
            if (e == null) {
                return;
            }
            Location c = e.getLocation();
            e.remove();
            if (mm == null) {
                return;
            }
            LivingEntity tt = currentTarget(b);
            double td = tt == null ? 0 : estimate(4, c, tt);
            double sd = estimate(4, c, mm);
            if (xbow) {
                mm.swingMainHand();
            }
            if (td >= minT && sd <= Math.min(num(b, "cart.max-self-damage"), mm.getHealth() - 1.0)) {
                c.getWorld().createExplosion(c, 4f, false, on(b, "cart.block-damage"), mm);
            }
        }, xbow ? 10L : 2L);
        return true;
    }

    private int findBlockSlot(Bot b) {
        boolean keepObsidian = on(b, "crystal.enabled") || on(b, "anchor.enabled");
        return b.findIf(i -> {
            Material mt = i.getType();
            if (!mt.isBlock() || !mt.isSolid() || mgr.blocked(mt)) {
                return false;
            }
            if (mt == Material.TNT || mt == Material.RESPAWN_ANCHOR || mt == Material.GLOWSTONE
                    || mt.name().endsWith("SHULKER_BOX")) {
                return false;
            }
            return !(mt == Material.OBSIDIAN && keepObsidian);
        }, false);
    }

    /** With no pearls but blocks in the inventory: bridge over gaps and pillar up toward the target. */
    private void bridge(Bot b, Mannequin m, LivingEntity t) {
        Bot.CombatState st = b.st;
        if (!on(b, "bridge.enabled") || !st.ready("bridge") || b.immovable || st.flight != 0) {
            return;
        }
        if ((on(b, "pearl.enabled") || on(b, "pcrit.enabled")) && b.find(Material.ENDER_PEARL) >= 0) {
            return;
        }
        int slot = findBlockSlot(b);
        if (slot < 0) {
            return;
        }
        Location ml = m.getLocation();
        Location tl = t.getLocation();
        double dx = tl.getX() - ml.getX();
        double dz = tl.getZ() - ml.getZ();
        double h = Math.hypot(dx, dz);
        double dy = tl.getY() - ml.getY();
        if (h > 40) {
            return;
        }
        Material mat = b.inv[slot].getType();
        int cleanup = intv(b, "bridge.cleanup-ticks");
        Block feet = ml.getBlock();

        if (on(b, "bridge.pillar") && dy >= 2.5 && h < 5) {
            if (m.isOnGround()) {
                Vector v = m.getVelocity();
                m.setVelocity(new Vector(v.getX(), 0.42, v.getZ()));
                return;
            }
            Block below = feet.getRelative(BlockFace.DOWN);
            if (m.getVelocity().getY() < 0.25 && below.isEmpty() && mgr.placeTemp(b, below, mat, cleanup, null, null)) {
                if (on(b, "items.consume")) {
                    b.take(slot, 1);
                }
                m.swingMainHand();
                st.setCd("bridge", 5);
            }
            return;
        }
        if (h > 2.0 && m.isOnGround()) {
            double nx = dx / h;
            double nz = dz / h;
            Block ahead = ml.clone().add(nx, 0, nz).getBlock();
            Block floorAhead = ahead.getRelative(BlockFace.DOWN);
            if (floorAhead.isEmpty() && floorAhead.getRelative(BlockFace.DOWN).isEmpty()
                    && mgr.placeTemp(b, floorAhead, mat, cleanup, null, null)) {
                if (on(b, "items.consume")) {
                    b.take(slot, 1);
                }
                m.swingMainHand();
                st.setCd("bridge", 4);
            }
        }
    }

    // ------------------------------------------------------------ pearl catch + grapple

    private boolean airborneHigh(LivingEntity t, double minHeight) {
        if (t.isOnGround()) {
            return false;
        }
        Location l = t.getLocation();
        for (int i = 1; i <= (int) Math.ceil(minHeight); i++) {
            if (!l.clone().add(0, -i, 0).getBlock().isPassable()) {
                return false;
            }
        }
        return true;
    }

    private boolean throwTrackedPearl(Bot b, Mannequin m, Vector dir, String plan, int slot) {
        Location eye = m.getEyeLocation();
        mgr.face(m, eye.clone().add(dir.clone().multiply(5)));
        EnderPearl pearl = m.launchProjectile(EnderPearl.class, dir);
        m.swingMainHand();
        if (on(b, "items.consume")) {
            b.take(slot, 1);
        }
        b.st.pearlId = pearl.getUniqueId();
        b.st.pearlPlan = plan;
        b.st.pearlAge = 0;
        return true;
    }

    /**
     * Pearl catch: throw a pearl, wind charge at your feet right after, and land above the target.
     * Grapple: pearl at a target that is mid-air, teleport to them and mace them.
     * Returns true while a throw is in flight (the bot stands and watches).
     */
    private boolean pearlTech(Bot b, Mannequin m, LivingEntity t, double dist) {
        Bot.CombatState st = b.st;
        if (st.pearlId != null) {
            st.pearlAge++;
            Entity pe = Bukkit.getEntity(st.pearlId);
            if (pe == null || pe.isDead() || st.pearlAge > 200) {
                st.pearlId = null;
                st.pearlPlan = "";
                return false;
            }
            if (st.windIn > 0 && --st.windIn == 0) {
                int wc = b.find(Material.WIND_CHARGE);
                if (wc >= 0 && !b.immovable) {
                    Vector v = m.getVelocity();
                    m.setRotation(m.getLocation().getYaw(), 90f);
                    m.setVelocity(new Vector(v.getX(), 0.75, v.getZ()));
                    m.launchProjectile(WindCharge.class, new Vector(0, -1, 0));
                    m.swingMainHand();
                    if (on(b, "items.consume")) {
                        b.take(wc, 1);
                    }
                }
            }
            if (st.pearlPlan.equals("grapple")) {
                aimAt(b, m, aimPoint(t));
            }
            return true;
        }
        if (b.immovable) {
            return false;
        }
        int pslot = b.find(Material.ENDER_PEARL);
        if (pslot < 0) {
            return false;
        }
        Location eye = m.getEyeLocation();

        if (on(b, "grapple.enabled") && st.ready("grapple") && dist >= 5.0 && dist <= num(b, "grapple.range")
                && airborneHigh(t, num(b, "grapple.min-height"))) {
            double flight = Math.max(1.0, dist / 1.4);
            Location p = aimPoint(t);
            p.add(t.getVelocity().multiply(flight * 0.8));
            p.add(0, 0.5 * 0.03 * flight * flight * 0.5, 0);
            Vector dir = p.toVector().subtract(eye.toVector()).normalize().multiply(1.5);
            st.setCd("grapple", intv(b, "grapple.cooldown"));
            st.windIn = 0;
            return throwTrackedPearl(b, m, dir, "grapple", pslot);
        }

        if (on(b, "pcatch.enabled") && st.ready("pcatch") && m.isOnGround()
                && dist >= num(b, "pcatch.min-range") && dist <= num(b, "pcatch.max-range")
                && b.find(Material.WIND_CHARGE) >= 0 && findWeapon(b, "MACE") >= 0) {
            Vector u = t.getLocation().toVector().subtract(m.getLocation().toVector()).setY(0);
            if (u.lengthSquared() < 1e-6) {
                return false;
            }
            u.normalize();
            String mode = str(b, "pcatch.mode");
            double dy = t.getLocation().getY() - m.getLocation().getY();
            if (mode.equals("auto")) {
                mode = dy < -5 ? "down" : "up";
            }
            Vector dir = switch (mode) {
                case "horizontal" -> u.clone().setY(0.25);
                case "down" -> u.clone().multiply(0.8).setY(-0.15);
                default -> u.clone().multiply(0.35).setY(1.0);
            };
            dir.normalize().multiply(1.5);
            st.setCd("pcatch", intv(b, "pcatch.cooldown"));
            st.windIn = Math.max(1, intv(b, "pcatch.wind-delay"));
            return throwTrackedPearl(b, m, dir, "pcatch", pslot);
        }
        return false;
    }

    /** A bot's pearl hit something: teleport the bot there and continue the technique. */
    public void onPearlLand(Bot b, Mannequin m, Location at) {
        Bot.CombatState st = b.st;
        String plan = st.pearlPlan;
        st.pearlId = null;
        st.pearlPlan = "";
        st.windIn = 0;
        if (at.getWorld() == null || !at.getWorld().equals(m.getWorld())) {
            return;
        }
        Location dest = at.clone();
        for (int i = 0; i < 3 && !dest.getBlock().isPassable(); i++) {
            dest.add(0, 1, 0);
        }
        double bonusFall = 0;
        if (plan.equals("pcatch")) {
            double want = num(b, "pcatch.height");
            int free = 0;
            while (free < want && dest.clone().add(0, free + 2, 0).getBlock().isPassable()) {
                free++;
            }
            dest.add(0, free, 0);
        } else if (plan.equals("grapple")) {
            Location l = dest.clone();
            while (bonusFall < 10 && l.clone().add(0, -bonusFall - 1, 0).getBlock().isPassable()) {
                bonusFall++;
            }
        }
        Location cur = m.getLocation();
        dest.setYaw(cur.getYaw());
        dest.setPitch(cur.getPitch());
        m.teleport(dest);
        m.setVelocity(new Vector(0, plan.equals("pcatch") ? 0.1 : -0.1, 0));
        st.peakY = dest.getY() + bonusFall;
        m.getWorld().playSound(dest, Sound.ENTITY_PLAYER_TELEPORT, 1f, 1f);
        if (on(b, "pearl.damage") && m.getHealth() > 6.0) {
            m.setHealth(m.getHealth() - 5.0);
        }
        int mace = findWeapon(b, "MACE");
        if (mace >= 0 && !plan.isEmpty()) {
            select(b, mace);
            st.noSelect = 20;
        }
    }

    // ------------------------------------------------------------ pearls

    private boolean pearl(Bot b, Mannequin m, LivingEntity t, double dist) {
        if ((!on(b, "pearl.enabled") && !on(b, "pcrit.enabled")) || !b.st.ready("pearl")) {
            return false;
        }
        int slot = b.find(Material.ENDER_PEARL);
        if (slot < 0) {
            return false;
        }
        boolean escape = on(b, "pearl.escape") && m.getHealth() <= num(b, "pearl.escape-health") && dist < 10
                && b.st.turtleTicks <= 0;
        boolean close = dist > (on(b, "pcrit.enabled") ? 6.0 : num(b, "pearl.min-distance"));
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
