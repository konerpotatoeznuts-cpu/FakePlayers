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
 * MATERIAL [amount] [enchantment=level ...] [potion=type]
 * Example: NETHERITE_SWORD sharpness=5 unbreaking=3
 */
public final class Kits {

    private Kits() {
    }

    /** Returns an error message, or null on success. */
    public static String apply(FakePlayersPlugin plugin, Bot b, String name) {
        List<String> lines = plugin.getConfig().getStringList("pvp.kits." + name);
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
        b.equipDirty = true;
        return null;
    }

    private static ItemStack parse(FakePlayersPlugin plugin, String line) {
        String[] parts = line.trim().split("\\s+");
        if (parts.length == 0 || parts[0].isEmpty()) {
            return null;
        }
        Material mat = Material.matchMaterial(parts[0]);
        if (mat == null || mat.isAir()) {
            return null;
        }
        ItemStack item = new ItemStack(mat);
        for (int i = 1; i < parts.length; i++) {
            String p = parts[i];
            if (p.contains("=")) {
                String[] kv = p.split("=", 2);
                String key = kv[0].toLowerCase(Locale.ROOT);
                if (key.equals("potion")) {
                    PotionType pt = Registry.POTION.get(NamespacedKey.minecraft(kv[1].toLowerCase(Locale.ROOT)));
                    if (pt != null && item.getItemMeta() instanceof PotionMeta pm) {
                        pm.setBasePotionType(pt);
                        item.setItemMeta(pm);
                    }
                } else {
                    Enchantment ench = Registry.ENCHANTMENT.get(NamespacedKey.minecraft(key));
                    if (ench != null) {
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
