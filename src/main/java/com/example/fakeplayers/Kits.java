package com.example.fakeplayers;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.potion.PotionType;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Loadouts from config.yml (pvp.kits.NAME). Line format:
 * MATERIAL [amount] [name=\"Text\"] [lore=\"a|b\"] [enchantment=level ...] [potion=type]
 * Example: NETHERITE_SWORD name=\"<red>Fang\" sharpness=5 unbreaking=3
 */
public final class Kits {

    private Kits() {
    }

    /** Returns an error message, or null on success. */
    public static String apply(FakePlayersPlugin plugin, Bot b, String name) {
        List<String> lines = plugin.kitLines(name);
        if (lines.isEmpty()) {
            return "No kit named " + name + " in config.yml (pvp.kits).";
        }
        Arrays.fill(b.inv, null);
        Arrays.fill(b.armor, null);
        b.offhand = null;
        b.selected = 0;
        for (String line : lines) {
            ItemStack item = parse(plugin, line);
            if (item == null) {
                plugin.getLogger().warning("Bad kit line in " + name + ": " + line);
                continue;
            }
            String n = item.getType().name();
            if (n.endsWith("_HELMET")) {
                b.armor[0] = item;
            } else if (n.endsWith("_CHESTPLATE") || n.equals("ELYTRA")) {
                b.armor[1] = item;
            } else if (n.endsWith("_LEGGINGS")) {
                b.armor[2] = item;
            } else if (n.endsWith("_BOOTS")) {
                b.armor[3] = item;
            } else if (item.getType() == Material.SHIELD && b.offhand == null) {
                b.offhand = item;
            } else {
                b.give(item);
            }
        }
        if (b.offhand == null) {
            int s = b.find(Material.TOTEM_OF_UNDYING);
            if (s >= 0) {
                b.take(s, 1);
                b.offhand = new ItemStack(Material.TOTEM_OF_UNDYING);
            }
        }
        Cosmetics.apply(b);
        b.equipDirty = true;
        b.lastKit = name;
        return null;
    }

    /** Short names used by the wiki so kit lines are easy to write. */
    private static final java.util.Map<String, String> ALIAS = java.util.Map.ofEntries(
            java.util.Map.entry("prot", "protection"), java.util.Map.entry("blast", "blast_protection"),
            java.util.Map.entry("blast_prot", "blast_protection"), java.util.Map.entry("fire_prot", "fire_protection"),
            java.util.Map.entry("proj_prot", "projectile_protection"), java.util.Map.entry("sweeping", "sweeping_edge"),
            java.util.Map.entry("unb", "unbreaking"), java.util.Map.entry("feather", "feather_falling"),
            java.util.Map.entry("ff", "feather_falling"), java.util.Map.entry("depth", "depth_strider"),
            java.util.Map.entry("aqua", "aqua_affinity"), java.util.Map.entry("resp", "respiration"),
            java.util.Map.entry("qc", "quick_charge"), java.util.Map.entry("eff", "efficiency"),
            java.util.Map.entry("sharp", "sharpness"), java.util.Map.entry("kb", "knockback"),
            java.util.Map.entry("fire", "fire_aspect"), java.util.Map.entry("wind", "wind_burst"));

    private static List<String> tokens(String line) {
        List<String> out = new java.util.ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean q = false;
        for (char c : line.trim().toCharArray()) {
            if (c == '"') {
                q = !q;
            } else if (Character.isWhitespace(c) && !q) {
                if (cur.length() > 0) {
                    out.add(cur.toString());
                    cur.setLength(0);
                }
            } else {
                cur.append(c);
            }
        }
        if (cur.length() > 0) {
            out.add(cur.toString());
        }
        return out;
    }

    /** MATERIAL [amount] [name="Text"] [lore="a|b"] [unbreakable] [enchant=level ...] [potion=type]. */
    static ItemStack parse(FakePlayersPlugin plugin, String line) {
        List<String> parts = tokens(line);
        if (parts.isEmpty()) {
            return null;
        }
        Material mat = Material.matchMaterial(parts.get(0));
        if (mat == null || mat.isAir()) {
            return null;
        }
        ItemStack item = new ItemStack(mat);
        for (int i = 1; i < parts.size(); i++) {
            String p = parts.get(i);
            if (p.equalsIgnoreCase("unbreakable")) {
                org.bukkit.inventory.meta.ItemMeta um = item.getItemMeta();
                um.setUnbreakable(true);
                item.setItemMeta(um);
            } else if (p.contains("=")) {
                String[] kv = p.split("=", 2);
                String key = kv[0].toLowerCase(Locale.ROOT);
                if (key.equals("potion")) {
                    PotionType pt = Registry.POTION.get(NamespacedKey.minecraft(kv[1].toLowerCase(Locale.ROOT)));
                    if (pt != null && item.getItemMeta() instanceof PotionMeta pm) {
                        pm.setBasePotionType(pt);
                        item.setItemMeta(pm);
                    }
                } else if (key.equals("name")) {
                    org.bukkit.inventory.meta.ItemMeta nm = item.getItemMeta();
                    nm.displayName(plugin.parse("<!italic>" + kv[1]));
                    item.setItemMeta(nm);
                } else if (key.equals("lore")) {
                    org.bukkit.inventory.meta.ItemMeta lm = item.getItemMeta();
                    List<net.kyori.adventure.text.Component> ll = new java.util.ArrayList<>();
                    for (String l : kv[1].split("\\|")) {
                        ll.add(plugin.parse("<!italic><gray>" + l));
                    }
                    lm.lore(ll);
                    item.setItemMeta(lm);
                } else {
                    String ek = ALIAS.getOrDefault(key, key);
                    Enchantment ench = Registry.ENCHANTMENT.get(NamespacedKey.minecraft(ek));
                    if (ench == null) {
                        plugin.getLogger().warning("Unknown enchantment '" + key + "' in kit line: " + line);
                    } else {
                        try {
                            item.addUnsafeEnchantment(ench, Integer.parseInt(kv[1]));
                        } catch (NumberFormatException ignored) {
                            // skip bad level
                        }
                    }
                }
            } else {
                try {
                    item.setAmount(Math.max(1, Math.min(item.getMaxStackSize(), Integer.parseInt(p))));
                } catch (NumberFormatException ignored) {
                    // skip
                }
            }
        }
        return item;
    }
}
