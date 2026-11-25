package com.example.fakeplayers;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mannequin;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.List;

/** Click-to-go navigation for linked bots: walking, building up, digging down, spreading out, attacking. */
public class BotNav {

    private final FakePlayersPlugin plugin;
    private final BotManager mgr;

    public BotNav(FakePlayersPlugin plugin, BotManager mgr) {
        this.plugin = plugin;
        this.mgr = mgr;
    }

    // ------------------------------------------------------------ orders

    public void sendTo(List<Bot> bots, Location target, boolean sneak) {
        List<Location> spots = spread(target, bots);
        for (int i = 0; i < bots.size(); i++) {
            Bot b = bots.get(i);
            endAttack(b);
            b.navGoal = spots.get(i);
            b.stealth = sneak && PvpOptions.bool(plugin, b, "nav.stealth-click");
            b.navTicks = 0;
            b.navStuck = 0;
            b.navLast = null;
            b.navDig = -1;
            b.navDigAt = null;
            clearPath(b);
            b.queue.clear();
            b.walkTarget = null;
            b.waitTicks = 0;
        }
    }

    public void teleportTo(List<Bot> bots, Location target) {
        List<Location> spots = spread(target, bots);
        for (int i = 0; i < bots.size(); i++) {
            Bot b = bots.get(i);
            Mannequin m = mgr.entity(b);
            if (m == null) {
                continue;
            }
            endAttack(b);
            b.navGoal = null;
            Location s = spots.get(i);
            Location cur = m.getLocation();
            s.setYaw(cur.getYaw());
            s.setPitch(cur.getPitch());
            m.teleport(s);
            m.setVelocity(new Vector(0, 0, 0));
        }
    }

    /** Makes the bots attack an entity even when their PvP option is off. */
    public void attack(List<Bot> bots, LivingEntity victim, boolean sneak) {
        for (Bot b : bots) {
            if (b.clickTarget == null) {
                b.combatBefore = b.combat;
            }
            b.clickTarget = victim.getUniqueId();
            b.stealth = sneak && PvpOptions.bool(plugin, b, "nav.stealth-click");
            b.combat = true;
            b.navGoal = null;
        }
    }

    public void endAttack(Bot b) {
        if (b.clickTarget == null) {
            return;
        }
        b.clickTarget = null;
        b.stealth = false;
        b.combat = b.combatBefore;
        b.resetCombat();
    }

    /** Stops the order when the attack target is gone. */
    public void checkAttack(Bot b, Mannequin m) {
        if (b.clickTarget == null) {
            return;
        }
        Entity e = Bukkit.getEntity(b.clickTarget);
        if (!(e instanceof LivingEntity le) || le.isDead() || !le.getWorld().equals(m.getWorld())) {
            endAttack(b);
        }
    }

    // ------------------------------------------------------------ spreading

    private List<Location> spread(Location t, List<Bot> bots) {
        int n = bots.size();
        List<Location> out = new ArrayList<>();
        if (n <= 1) {
            out.add(surface(t));
            return out;
        }
        Bot first = bots.get(0);
        double step = Math.max(0.8, PvpOptions.dbl(plugin, first, "nav.spread-radius"));
        if ("scattered".equals(PvpOptions.str(plugin, first, "nav.spread"))) {
            java.util.concurrent.ThreadLocalRandom rnd = java.util.concurrent.ThreadLocalRandom.current();
            double max = step * (0.8 + Math.sqrt(n));
            List<double[]> used = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                double[] best = null;
                for (int tries = 0; tries < 20; tries++) {
                    double ang = rnd.nextDouble() * 2 * Math.PI;
                    double r = max * Math.sqrt(rnd.nextDouble());
                    double[] p = {Math.cos(ang) * r, Math.sin(ang) * r};
                    best = p;
                    boolean ok = true;
                    for (double[] u : used) {
                        if (Math.hypot(u[0] - p[0], u[1] - p[1]) < 1.2) {
                            ok = false;
                            break;
                        }
                    }
                    if (ok) {
                        break;
                    }
                }
                used.add(best);
                out.add(surface(t.clone().add(best[0], 0, best[1])));
            }
            return out;
        }
        int per = 6;
        for (int i = 0; i < n; i++) {
            int ring = i / per + 1;
            int inRing = Math.min(per, n - (ring - 1) * per);
            int idx = i % per;
            double ang = 2 * Math.PI * idx / inRing + ring * 0.6;
            double r = step * ring;
            out.add(surface(t.clone().add(Math.cos(ang) * r, 0, Math.sin(ang) * r)));
        }
        return out;
    }

    /** Finds a standable spot near this height at the column of the location. */
    private Location surface(Location l) {
        World w = l.getWorld();
        int x = l.getBlockX();
        int z = l.getBlockZ();
        int y0 = (int) Math.floor(l.getY());
        for (int k = 0; k <= 6; k++) {
            for (int sgn = 1; sgn >= -1; sgn -= 2) {
                if (k == 0 && sgn < 0) {
                    continue;
                }
                int y = y0 + sgn * k;
                Block feet = w.getBlockAt(x, y, z);
                if (feet.isPassable() && !feet.isLiquid() && feet.getRelative(BlockFace.UP).isPassable()
                        && !feet.getRelative(BlockFace.DOWN).isPassable()) {
                    return new Location(w, x + 0.5, y, z + 0.5);
                }
            }
        }
        return new Location(w, x + 0.5, l.getY(), z + 0.5);
    }

    // ------------------------------------------------------------ pathfinding

    public enum PathState { MOVING, ARRIVED, FAILED }

    public void clearPath(Bot b) {
        b.path = null;
        b.pathGoal = null;
        b.pathIdx = 0;
        b.pathCd = 0;
        b.pathStuck = 0;
        b.pathLast = null;
    }

    /** Walks toward a goal along an A* route. FAILED = no (further) walkable route; the caller may dig or build. */
    public PathState walkPath(Bot b, Mannequin m, Location goal) {
        Location cur = m.getLocation();
        if (!PvpOptions.bool(plugin, b, "nav.pathfinding")) {
            return mgr.moveToward(b, m, goal) ? PathState.ARRIVED : PathState.MOVING;
        }
        if (goal.getWorld() == null || !goal.getWorld().equals(cur.getWorld())) {
            return PathState.FAILED;
        }
        double h = Math.hypot(goal.getX() - cur.getX(), goal.getZ() - cur.getZ());
        double dy = goal.getY() - cur.getY();
        if (h < 0.8 && Math.abs(dy) < 1.6) {
            return PathState.ARRIVED;
        }
        if (b.pathCd > 0) {
            b.pathCd--;
        }
        b.pathTicks++;

        // stuck detection while following a route
        if (b.pathTicks % 10 == 0) {
            if (b.pathLast != null && b.pathLast.getWorld() == cur.getWorld() && b.pathLast.distanceSquared(cur) < 0.04) {
                b.pathStuck++;
            } else {
                b.pathStuck = 0;
            }
            b.pathLast = cur.clone();
        }

        boolean need = b.path == null || b.pathGoal == null
                || b.pathGoal.getWorld() != goal.getWorld() || b.pathGoal.distanceSquared(goal) > 4.0
                || b.pathStuck >= 2 || (b.pathPartial && b.pathTicks > 80);
        if (!need && b.path != null && b.pathIdx < b.path.size()
                && Math.abs(b.path.get(b.pathIdx).getY() - cur.getY()) > 4.0) {
            need = true;
        }
        if (need) {
            if (b.pathCd > 0) {
                return b.path == null || b.pathIdx >= b.path.size() ? PathState.FAILED : follow(b, m, goal);
            }
            BotPath.Result r = BotPath.find(cur, goal,
                    Math.max(500, PvpOptions.integer(plugin, b, "nav.path-nodes")),
                    Math.max(0, PvpOptions.integer(plugin, b, "nav.max-drop")));
            b.pathCd = 10;
            b.pathTicks = 0;
            b.pathStuck = 0;
            b.pathGoal = goal.clone();
            b.pathIdx = 0;
            if (r == null) {
                b.path = null;
                return PathState.FAILED;
            }
            b.path = r.nodes();
            b.pathPartial = !r.complete();
            // skip the node we are standing on
            while (b.pathIdx < b.path.size() && reached(cur, b.path.get(b.pathIdx))) {
                b.pathIdx++;
            }
        }
        return follow(b, m, goal);
    }

    private boolean reached(Location cur, Location node) {
        return Math.hypot(node.getX() - cur.getX(), node.getZ() - cur.getZ()) < 0.5
                && Math.abs(node.getY() - cur.getY()) < 1.3;
    }

    private PathState follow(Bot b, Mannequin m, Location goal) {
        Location cur = m.getLocation();
        while (b.pathIdx < b.path.size() && reached(cur, b.path.get(b.pathIdx))) {
            b.pathIdx++;
        }
        if (b.pathIdx >= b.path.size()) {
            if (b.pathPartial) {
                return PathState.FAILED;
            }
            mgr.moveToward(b, m, goal);
            return PathState.MOVING;
        }
        mgr.moveToward(b, m, b.path.get(b.pathIdx));
        return PathState.MOVING;
    }

    // ------------------------------------------------------------ tick

    /** Returns true while the bot is busy travelling to a clicked block. */
    public boolean tick(Bot b, Mannequin m) {
        if (b.stealth) {
            applyStealth(b, m, true);
        } else if (b.stealthOn) {
            applyStealth(b, m, false);
        }
        Location goal = b.navGoal;
        if (goal == null) {
            return false;
        }
        if (b.immovable || goal.getWorld() == null || !goal.getWorld().equals(m.getWorld())) {
            b.navGoal = null;
            return false;
        }
        Location l = m.getLocation();
        double dx = goal.getX() - l.getX();
        double dz = goal.getZ() - l.getZ();
        double h = Math.hypot(dx, dz);
        double dy = goal.getY() - l.getY();
        if (++b.navTicks > 900 || (h < 0.8 && Math.abs(dy) < 1.5)) {
            b.stealth = false;
            applyStealth(b, m, false);
            b.navGoal = null;
            b.navLast = null;
            clearPath(b);
            return false;
        }
        PathState ps = walkPath(b, m, goal);
        if (ps == PathState.MOVING) {
            b.navDigAt = null;
            b.navDig = -1;
            return true;
        }
        if (ps == PathState.ARRIVED) {
            b.stealth = false;
            applyStealth(b, m, false);
            b.navGoal = null;
            clearPath(b);
            return false;
        }

        // no walkable route (or the closest reachable point): build up or dig, only now
        boolean build = PvpOptions.bool(plugin, b, "nav.build");
        boolean dig = PvpOptions.bool(plugin, b, "nav.dig");
        if (dy > 1.2 && build && pillar(b, m)) {
            return true;
        }
        if (dy < -1.2 && h < 2.5 && dig && digDown(b, m)) {
            return true;
        }
        if (dig && h > 0.9 && digAhead(b, m, dx / h, dz / h)) {
            return true;
        }
        b.navDigAt = null;
        b.navDig = -1;
        mgr.moveToward(b, m, goal);
        return true;
    }

    // ------------------------------------------------------------ stealth

    /** Sneaking, silent and nameless: the bot is as hard to notice as it can be. */
    private void applyStealth(Bot b, Mannequin m, boolean on) {
        if (on) {
            if (!b.stealthOn || m.getPose() != org.bukkit.entity.Pose.SNEAKING) {
                m.setPose(org.bukkit.entity.Pose.SNEAKING, true);
                m.setSilent(true);
                m.setCustomNameVisible(false);
                b.stealthOn = true;
            }
        } else if (b.stealthOn) {
            m.setPose(org.bukkit.entity.Pose.STANDING, false);
            m.setSilent(false);
            m.setCustomNameVisible(true);
            b.stealthOn = false;
        }
    }

    /** True when the bot is inside the target's field of view and in plain sight. */
    private boolean spotted(Bot b, Mannequin m, LivingEntity t) {
        Location eye = t.getEyeLocation();
        Vector to = m.getEyeLocation().toVector().subtract(eye.toVector());
        double dist = to.length();
        if (dist > 48 || dist < 1e-6) {
            return false;
        }
        double dot = eye.getDirection().normalize().dot(to.normalize());
        double angle = Math.toDegrees(Math.acos(Math.max(-1, Math.min(1, dot))));
        return angle <= PvpOptions.dbl(plugin, b, "nav.stealth-spot-angle") && t.hasLineOfSight(m);
    }

    /**
     * Stealth attack: sneak around to the target's back and only start fighting from there.
     * Returns true while the bot is still creeping up.
     */
    public boolean stealthTick(Bot b, Mannequin m) {
        if (!b.stealth || b.clickTarget == null) {
            if (b.stealthOn) {
                applyStealth(b, m, false);
            }
            return false;
        }
        Entity e = Bukkit.getEntity(b.clickTarget);
        if (!(e instanceof LivingEntity t) || t.isDead() || !t.getWorld().equals(m.getWorld()) || b.immovable) {
            b.stealth = false;
            applyStealth(b, m, false);
            return false;
        }
        if (PvpOptions.bool(plugin, b, "nav.stealth-spot-drop") && spotted(b, m, t)) {
            // the target saw one of them: everyone going for this target loses stealth
            for (Bot o : mgr.all()) {
                if (b.clickTarget.equals(o.clickTarget) && o.stealth) {
                    o.stealth = false;
                    o.st.reaction = 0;
                    o.st.attackTimer = 0;
                    Mannequin om = mgr.entity(o);
                    if (om != null) {
                        applyStealth(o, om, false);
                    }
                }
            }
            return false;
        }
        applyStealth(b, m, true);
        Location tl = t.getLocation();
        Vector look = t.getEyeLocation().getDirection().setY(0);
        if (look.lengthSquared() < 1e-6) {
            look = new Vector(0, 0, 1);
        }
        look.normalize();
        Vector toBot = m.getLocation().toVector().subtract(tl.toVector()).setY(0);
        double dist = toBot.length();
        double dot = dist < 1e-6 ? -1 : look.dot(toBot.clone().normalize());
        b.navTicks++;
        if ((dist <= 2.6 && dot < 0.0) || b.navTicks > 900 || dist < 1.2) {
            b.stealth = false;
            b.navTicks = 0;
            applyStealth(b, m, false);
            b.st.reaction = 0;
            b.st.attackTimer = 0;
            return false;
        }
        Location goal;
        if (dot > 0.0 && dist < 12) {
            Vector perp = new Vector(-look.getZ(), 0, look.getX());
            double sg = Math.signum(perp.dot(toBot));
            if (sg == 0) {
                sg = 1;
            }
            goal = tl.clone().add(perp.multiply(sg * 5.0)).subtract(look.clone().multiply(0.5));
        } else {
            goal = tl.clone().subtract(look.multiply(1.6));
        }
        mgr.moveToward(b, m, goal);
        return true;
    }

    // ------------------------------------------------------------ building up

    private boolean badBlock(Material mt) {
        String n = mt.name();
        return !mt.isBlock() || !mt.isSolid() || !mt.isOccluding() || mgr.blocked(mt)
                || n.contains("SAND") || n.contains("GRAVEL") || n.contains("POWDER") || n.contains("ANVIL")
                || n.contains("TNT") || n.contains("SHULKER") || n.contains("CHEST") || n.contains("BARREL")
                || n.contains("FURNACE") || n.contains("BED");
    }

    private boolean pillar(Bot b, Mannequin m) {
        int slot = b.findIf(i -> !badBlock(i.getType()), false);
        if (slot < 0) {
            return false;
        }
        Material mat = b.inv[slot].getType();
        Location l = m.getLocation();
        if (m.isOnGround()) {
            Vector v = m.getVelocity();
            m.setRotation(l.getYaw(), 90f);
            m.setVelocity(new Vector(v.getX() * 0.2, 0.42, v.getZ() * 0.2));
            return true;
        }
        Block below = l.getBlock().getRelative(BlockFace.DOWN);
        if ((below.isEmpty() || below.isReplaceable()) && mgr.placeTemp(b, below, mat,
                Math.max(0, PvpOptions.integer(plugin, b, "bridge.cleanup-ticks")), null, null)) {
            m.setRotation(l.getYaw(), 90f);
            m.swingMainHand();
            if (plugin.getConfig().getBoolean("build.consume-items", false)) {
                b.take(slot, 1);
            }
        }
        return true;
    }

    // ------------------------------------------------------------ digging

    private boolean digDown(Bot b, Mannequin m) {
        if (!m.isOnGround()) {
            return true;
        }
        Block below = m.getLocation().getBlock().getRelative(BlockFace.DOWN);
        if (below.isPassable()) {
            return false;
        }
        Material under = below.getRelative(BlockFace.DOWN).getType();
        if (under == Material.LAVA || under == Material.MAGMA_BLOCK || under == Material.FIRE) {
            return false;
        }
        m.setRotation(m.getLocation().getYaw(), 90f);
        return digBlock(b, m, below);
    }

    private boolean digAhead(Bot b, Mannequin m, double ux, double uz) {
        Location l = m.getLocation();
        Block feet = l.getWorld().getBlockAt(l.clone().add(ux * 0.9, 0, uz * 0.9));
        Block head = feet.getRelative(BlockFace.UP);
        Block high = l.getBlock().getRelative(BlockFace.UP, 2);
        for (Block t : new Block[]{feet, head, high}) {
            if (!t.isPassable()) {
                mgr.face(m, t.getLocation().add(0.5, 0.5, 0.5));
                return digBlock(b, m, t);
            }
        }
        b.navStuck = 0;
        return false;
    }

    /** Breaks one block over a few ticks (no drops). Returns false when the block can't be broken. */
    private boolean digBlock(Bot b, Mannequin m, Block block) {
        Material mt = block.getType();
        if (mgr.blocked(mt) || mt.getHardness() < 0 || block.isLiquid()) {
            return false;
        }
        Location at = block.getLocation();
        if (!at.equals(b.navDigAt)) {
            b.navDigAt = at;
            b.navDig = Math.max(3, Math.min(20, (int) Math.ceil(mt.getHardness() * 3)));
        }
        if (b.navDig % 4 == 0) {
            m.swingMainHand();
        }
        if (--b.navDig <= 0) {
            block.getWorld().spawnParticle(Particle.BLOCK, at.clone().add(0.5, 0.5, 0.5), 20, 0.3, 0.3, 0.3,
                    block.getBlockData());
            if (PvpOptions.bool(plugin, b, "nav.drops")) {
                block.breakNaturally(new org.bukkit.inventory.ItemStack(Material.NETHERITE_PICKAXE));
            } else {
                block.setType(Material.AIR);
            }
            b.navDigAt = null;
            b.navDig = -1;
            b.navStuck = 0;
            b.path = null;
            b.pathCd = 0;
        }
        return true;
    }
}
