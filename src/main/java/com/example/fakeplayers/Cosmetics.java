package com.example.fakeplayers;

import org.bukkit.DyeColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.block.Banner;
import org.bukkit.block.banner.Pattern;
import org.bukkit.block.banner.PatternType;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ArmorMeta;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.inventory.meta.trim.ArmorTrim;
import org.bukkit.inventory.meta.trim.TrimMaterial;
import org.bukkit.inventory.meta.trim.TrimPattern;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Per-bot shield banner design and armor trims. Applied on top of any kit or hand-placed gear. */
public final class Cosmetics {

    public static final String[] SLOTS = {"helmet", "chestplate", "leggings", "boots"};

    private Cosmetics() {
    }

    public static NamespacedKey key(String s) {
        return NamespacedKey.minecraft(s.toLowerCase(Locale.ROOT));
    }

    public static DyeColor color(String s) {
        try {
            return DyeColor.valueOf(s.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    public static List<PatternType> patternTypes() {
        List<PatternType> l = new ArrayList<>();
        Registry.BANNER_PATTERN.forEach(l::add);
        return l;
    }

    public static List<TrimPattern> trimPatterns() {
        List<TrimPattern> l = new ArrayList<>();
        Registry.TRIM_PATTERN.forEach(l::add);
        return l;
    }

    public static List<TrimMaterial> trimMaterials() {
        List<TrimMaterial> l = new ArrayList<>();
        Registry.TRIM_MATERIAL.forEach(l::add);
        return l;
    }

    public static String name(org.bukkit.Keyed k) {
        return k.getKey().getKey();
    }

    /** Applies the bot's saved banner and trims to every shield / armor piece it holds. */
    public static void apply(Bot b) {
        for (int i = 0; i < 4; i++) {
            b.armor[i] = trim(b.armor[i], b.trims[i]);
        }
        b.offhand = banner(b, b.offhand);
        for (int i = 0; i < b.inv.length; i++) {
            b.inv[i] = banner(b, b.inv[i]);
        }
        b.equipDirty = true;
    }

    public static ItemStack banner(Bot b, ItemStack item) {
        if (item == null || item.getType() != Material.SHIELD || b.bannerBase == null) {
            return item;
        }
        return shield(item, b.bannerBase, b.bannerLayers);
    }

    public static ItemStack shield(ItemStack item, String base, List<String> layers) {
        DyeColor bc = color(base);
        if (bc == null || !(item.getItemMeta() instanceof BlockStateMeta bsm)
                || !(bsm.getBlockState() instanceof Banner banner)) {
            return item;
        }
        banner.setBaseColor(bc);
        List<Pattern> ps = new ArrayList<>();
        for (String l : layers) {
            String[] kv = l.split(":", 2);
            if (kv.length < 2) {
                continue;
            }
            PatternType pt = Registry.BANNER_PATTERN.get(key(kv[0]));
            DyeColor c = color(kv[1]);
            if (pt != null && c != null) {
                ps.add(new Pattern(c, pt));
            }
        }
        banner.setPatterns(ps);
        bsm.setBlockState(banner);
        item.setItemMeta(bsm);
        return item;
    }

    public static ItemStack trim(ItemStack item, String spec) {
        if (item == null || spec == null || spec.isEmpty() || !(item.getItemMeta() instanceof ArmorMeta am)) {
            return item;
        }
        String[] kv = spec.split(":", 2);
        if (kv.length < 2) {
            return item;
        }
        TrimPattern tp = Registry.TRIM_PATTERN.get(key(kv[0]));
        TrimMaterial tm = Registry.TRIM_MATERIAL.get(key(kv[1]));
        if (tp == null || tm == null) {
            return item;
        }
        am.setTrim(new ArmorTrim(tm, tp));
        item.setItemMeta(am);
        return item;
    }

    /** Removes a trim from an item. */
    public static ItemStack untrim(ItemStack item) {
        if (item != null && item.getItemMeta() instanceof ArmorMeta am && am.hasTrim()) {
            am.setTrim(null);
            item.setItemMeta(am);
        }
        return item;
    }

    public static int slotIndex(String s) {
        return switch (s.toLowerCase(Locale.ROOT)) {
            case "helmet", "head" -> 0;
            case "chestplate", "chest" -> 1;
            case "leggings", "legs" -> 2;
            case "boots", "feet" -> 3;
            default -> -1;
        };
    }
}
