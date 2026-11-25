package com.example.fakeplayers;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

/**
 * A* pathfinding over walkable blocks: walks, hops up one block, drops up to a few blocks, avoids lava, fire,
 * cobwebs, cactus and so on. Does not dig or build; the caller falls back to that when no full route exists.
 */
public final class BotPath {

    public record Result(List<Location> nodes, boolean complete) {
    }

    private static final int SOLID_OK = 0;
    private static final int SOLID_NO = 1;   // fences, walls, hot floors: blocks and cannot be stood on
    private static final int FREE = 2;
    private static final int WATER = 3;
    private static final int DANGER = 4;     // passable but must not be entered

    private static final class Node {
        final int x;
        final int y;
        final int z;
        double g;
        double f;
        Node parent;
        boolean closed;

        Node(int x, int y, int z) {
            this.x = x;
            this.y = y;
            this.z = z;
        }
    }

    private final World w;
    private final Map<Long, Integer> cache = new HashMap<>();
    private final int minY;
    private final int maxY;

    private BotPath(World w) {
        this.w = w;
        this.minY = w.getMinHeight() + 1;
        this.maxY = w.getMaxHeight() - 3;
    }

    private static long key(int x, int y, int z) {
        return ((long) (x + 33554432) << 38) | ((long) (z + 33554432) << 12) | (long) (y + 2048);
    }

    // ------------------------------------------------------------ classification

    private int cls(int x, int y, int z) {
        if (y < minY || y > maxY) {
            return SOLID_NO;
        }
        long k = key(x, y, z);
        Integer c = cache.get(k);
        if (c != null) {
            return c;
        }
        int r;
        if (!w.isChunkLoaded(x >> 4, z >> 4)) {
            r = SOLID_NO;
        } else {
            Block b = w.getBlockAt(x, y, z);
            Material mt = b.getType();
            String n = mt.name();
            if (b.isPassable()) {
                if (mt == Material.LAVA || mt == Material.FIRE || mt == Material.SOUL_FIRE
                        || mt == Material.COBWEB || mt == Material.SWEET_BERRY_BUSH
                        || mt == Material.POWDER_SNOW || mt == Material.WITHER_ROSE
                        || mt == Material.POINTED_DRIPSTONE || n.contains("CAMPFIRE")) {
                    r = DANGER;
                } else if (mt == Material.WATER) {
                    r = WATER;
                } else {
                    r = FREE;
                }
            } else if (mt == Material.MAGMA_BLOCK || mt == Material.CACTUS || n.contains("CAMPFIRE")
                    || n.endsWith("FENCE") || n.endsWith("FENCE_GATE") || n.endsWith("_WALL")
                    || n.contains("DOOR") || n.contains("PANE") || n.equals("IRON_BARS")) {
                r = SOLID_NO;
            } else {
                r = SOLID_OK;
            }
        }
        cache.put(k, r);
        return r;
    }

    private boolean open(int c) {
        return c == FREE || c == WATER;
    }

    private boolean standable(int x, int y, int z) {
        return open(cls(x, y, z)) && open(cls(x, y + 1, z)) && cls(x, y - 1, z) == SOLID_OK;
    }

    // ------------------------------------------------------------ search

    public static Result find(Location from, Location to, int maxNodes, int maxDrop) {
        if (from.getWorld() == null || !from.getWorld().equals(to.getWorld())) {
            return null;
        }
        return new BotPath(from.getWorld()).search(from, to, maxNodes, maxDrop);
    }

    private int[] snap(int x, int y, int z, int up, int down) {
        for (int d = 0; d <= Math.max(up, down); d++) {
            if (d <= up && standable(x, y + d, z)) {
                return new int[]{x, y + d, z};
            }
            if (d > 0 && d <= down && standable(x, y - d, z)) {
                return new int[]{x, y - d, z};
            }
        }
        return null;
    }

    private Result search(Location from, Location to, int maxNodes, int maxDrop) {
        int[] s = snap(from.getBlockX(), from.getBlockY(), from.getBlockZ(), 1, 2);
        if (s == null) {
            return null;
        }
        int gx = to.getBlockX();
        int gy = to.getBlockY();
        int gz = to.getBlockZ();
        int[] g = snap(gx, gy, gz, 2, 4);
        if (g == null) {
            outer:
            for (int r = 1; r <= 2; r++) {
                for (int dx = -r; dx <= r; dx++) {
                    for (int dz = -r; dz <= r; dz++) {
                        g = snap(gx + dx, gy, gz + dz, 2, 4);
                        if (g != null) {
                            break outer;
                        }
                    }
                }
            }
        }
        double tx = gx + 0.5;
        double ty = gy;
        double tz = gz + 0.5;
        if (g != null) {
            tx = g[0] + 0.5;
            ty = g[1];
            tz = g[2] + 0.5;
        }

        Map<Long, Node> nodes = new HashMap<>();
        PriorityQueue<Node> open = new PriorityQueue<>((a, b) -> Double.compare(a.f, b.f));
        Node start = new Node(s[0], s[1], s[2]);
        start.g = 0;
        start.f = h(start, tx, ty, tz);
        nodes.put(key(start.x, start.y, start.z), start);
        open.add(start);
        Node best = start;
        double bestH = start.f;
        Node end = null;
        int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        int expanded = 0;
        int radius = 110;

        while (!open.isEmpty() && expanded < maxNodes) {
            Node n = open.poll();
            if (n.closed) {
                continue;
            }
            n.closed = true;
            expanded++;
            double hh = h(n, tx, ty, tz);
            if (hh < bestH) {
                bestH = hh;
                best = n;
            }
            if (g != null && n.x == g[0] && n.y == g[1] && n.z == g[2]) {
                end = n;
                break;
            }
            for (int[] d : dirs) {
                int nx = n.x + d[0];
                int nz = n.z + d[1];
                if (Math.abs(nx - s[0]) > radius || Math.abs(nz - s[2]) > radius) {
                    continue;
                }
                if (standable(nx, n.y, nz)) {
                    relax(nodes, open, n, nx, n.y, nz, 1.0 + (cls(nx, n.y, nz) == WATER ? 1.0 : 0.0), tx, ty, tz);
                } else if (open(cls(nx, n.y, nz)) && open(cls(nx, n.y + 1, nz))) {
                    // walk off an edge: drop a few blocks
                    for (int dd = 1; dd <= maxDrop; dd++) {
                        if (standable(nx, n.y - dd, nz)) {
                            relax(nodes, open, n, nx, n.y - dd, nz, 1.0 + 0.6 * dd, tx, ty, tz);
                            break;
                        }
                        if (!open(cls(nx, n.y - dd, nz))) {
                            break;
                        }
                    }
                } else if (cls(nx, n.y, nz) == SOLID_OK && open(cls(n.x, n.y + 2, n.z))
                        && standable(nx, n.y + 1, nz)) {
                    relax(nodes, open, n, nx, n.y + 1, nz, 2.0, tx, ty, tz);
                }
            }
        }

        boolean complete = end != null;
        Node last = complete ? end : best;
        List<int[]> pts = new ArrayList<>();
        for (Node c = last; c != null; c = c.parent) {
            pts.add(new int[]{c.x, c.y, c.z});
        }
        Collections.reverse(pts);
        if (pts.size() <= 1 && !complete) {
            return new Result(new ArrayList<>(), false);
        }
        List<Location> out = new ArrayList<>();
        for (int[] p : smooth(pts)) {
            out.add(new Location(w, p[0] + 0.5, p[1], p[2] + 0.5));
        }
        return new Result(out, complete);
    }

    private static double h(Node n, double tx, double ty, double tz) {
        double dx = n.x + 0.5 - tx;
        double dy = n.y - ty;
        double dz = n.z + 0.5 - tz;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private void relax(Map<Long, Node> nodes, PriorityQueue<Node> open, Node from, int x, int y, int z,
                       double cost, double tx, double ty, double tz) {
        long k = key(x, y, z);
        Node n = nodes.get(k);
        double g = from.g + cost;
        if (n == null) {
            n = new Node(x, y, z);
            nodes.put(k, n);
        } else if (n.closed || g >= n.g) {
            return;
        }
        n.g = g;
        n.parent = from;
        n.f = g + h(n, tx, ty, tz) * 1.1;
        open.add(n);
    }

    // ------------------------------------------------------------ smoothing

    /** Cuts corners over flat ground so the route looks like a person walking, not a staircase. */
    private List<int[]> smooth(List<int[]> p) {
        List<int[]> out = new ArrayList<>();
        int n = p.size();
        int i = 0;
        out.add(p.get(0));
        while (i < n - 1) {
            int j = Math.min(n - 1, i + 14);
            for (; j > i + 1; j--) {
                if (straight(p, i, j)) {
                    break;
                }
            }
            out.add(p.get(j));
            i = j;
        }
        return out;
    }

    private boolean straight(List<int[]> p, int i, int j) {
        int[] a = p.get(i);
        int[] b = p.get(j);
        for (int k = i; k <= j; k++) {
            if (p.get(k)[1] != a[1]) {
                return false;
            }
        }
        double dx = b[0] - a[0];
        double dz = b[2] - a[2];
        double len = Math.hypot(dx, dz);
        if (len < 1e-6) {
            return true;
        }
        double px = -dz / len * 0.32;
        double pz = dx / len * 0.32;
        int steps = (int) Math.ceil(len * 3);
        for (int s = 1; s < steps; s++) {
            double t = (double) s / steps;
            double cx = a[0] + 0.5 + dx * t;
            double cz = a[2] + 0.5 + dz * t;
            if (!standable((int) Math.floor(cx), a[1], (int) Math.floor(cz))
                    || !standable((int) Math.floor(cx + px), a[1], (int) Math.floor(cz + pz))
                    || !standable((int) Math.floor(cx - px), a[1], (int) Math.floor(cz - pz))) {
                return false;
            }
        }
        return true;
    }
}
