package com.example.fakeplayers;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.DyeColor;
import org.bukkit.Material;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Two editors in one holder: SHIELD (banner base colour + pattern layers) and TRIM (armor trims).
 * Slots: 9-24 dye colours (shield) / 9-19 trim materials, 27-44 patterns, 45 prev, 46 next,
 * 47 undo / clear piece, 48 clear all, 49 back, 53 close.
 */
public class StyleGui implements InventoryHolder {

    public enum Mode { SHIELD, TRIM }

    public static final int PAGE = 18;
    public static final int PATTERN_START = 27;
    public static final int PREV = 45;
    public static final int NEXT = 46;
    public static final int UNDO = 47;
    public static final int CLEAR = 48;
    public static final int BACK = 49;
    public static final int CLOSE = 53;
    public static final int APPLY_BASE = 0; // shield: set base colour
    public static final int PREVIEW = 4;

    private static final Material[] DYES = {
            Material.WHITE_DYE, Material.ORANGE_DYE, Material.MAGENTA_DYE, Material.LIGHT_BLUE_DYE,
            Material.YELLOW_DYE, Material.LIME_DYE, Material.PINK_DYE, Material.GRAY_DYE,
            Material.LIGHT_GRAY_DYE, Material.CYAN_DYE, Material.PURPLE_DYE, Material.BLUE_DYE,
            Material.BROWN_DYE, Material.GREEN_DYE, Material.RED_DYE, Material.BLACK_DYE};

    private final Bot bot;
    public final Mode mode;
    private final Inventory inventory;
    public int page = 0;
    public int color = 0;          // selected dye (shield)
    public int piece = -1;         // selected armor piece, -1 = all (trim)
    public int material = 0;       // selected trim material index
    private final List<String> patterns = new ArrayList<>();
    private final List<String> materials = new ArrayList<>();

    public StyleGui(Bot bot, Mode mode) {
        this.bot = bot;
        this.mode = mode;
        if (mode == Mode.SHIELD) {
            Cosmetics.patternTypes().forEach(p -> patterns.add(Cosmetics.name(p)));
        } else {
            Cosmetics.trimPatterns().forEach(p -> patterns.add(Cosmetics.name(p)));
            Cosmetics.trimMaterials().forEach(m -> materials.add(Cosmetics.name(m)));
        }
        this.inventory = Bukkit.createInventory(this, 54,
                Component.text((mode == Mode.SHIELD ? "Shield banner: " : "Armor trims: ") + bot.name));
        refresh();
    }

    public Bot bot() {
        return bot;
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    public int pageCount() {
        return Math.max(1, (patterns.size() + PAGE - 1) / PAGE);
    }

    public String patternAt(int slot) {
        int i = page * PAGE + (slot - PATTERN_START);
        return slot >= PATTERN_START && slot < PATTERN_START + PAGE && i < patterns.size() ? patterns.get(i) : null;
    }

    public String selectedMaterial() {
        return materials.isEmpty() ? null : materials.get(Math.min(material, materials.size() - 1));
    }

    public String selectedColorName() {
        return DyeColor.values()[color].name().toLowerCase(Locale.ROOT);
    }

    public void refresh() {
        inventory.clear();
        for (int i = 0; i < 54; i++) {
            if ((i >= 5 && i <= 8) || i == 1 || i == 2 || i == 3 || (i >= 25 && i <= 26) || i >= 45) {
                inventory.setItem(i, pane());
            }
        }
        if (mode == Mode.SHIELD) {
            shieldLayout();
        } else {
            trimLayout();
        }
        for (int s = 0; s < PAGE; s++) {
            String p = patternAt(PATTERN_START + s);
            inventory.setItem(PATTERN_START + s, p == null ? pane() : patternItem(p));
        }
        for (int i = PATTERN_START + PAGE; i < 45; i++) {
            inventory.setItem(i, pane());
        }
        inventory.setItem(PREV, button(Material.ARROW, "Previous page (" + (page + 1) + "/" + pageCount() + ")", NamedTextColor.YELLOW));
        inventory.setItem(NEXT, button(Material.ARROW, "Next page", NamedTextColor.YELLOW));
        inventory.setItem(BACK, button(Material.CHEST, "Back to bot menu", NamedTextColor.GREEN));
        inventory.setItem(CLOSE, button(Material.OAK_DOOR, "Close", NamedTextColor.GRAY));
    }

    private void shieldLayout() {
        ItemStack sh = Cosmetics.shield(new ItemStack(Material.SHIELD),
                bot.bannerBase == null ? "white" : bot.bannerBase, bot.bannerLayers);
        ItemMeta m = sh.getItemMeta();
        m.displayName(plain("Current shield", NamedTextColor.AQUA));
        List<Component> lore = new ArrayList<>();
        lore.add(plain("Base: " + (bot.bannerBase == null ? "default (none)" : bot.bannerBase), NamedTextColor.GRAY));
        for (String l : bot.bannerLayers) {
            lore.add(plain(" - " + l, NamedTextColor.GRAY));
        }
        m.lore(lore);
        sh.setItemMeta(m);
        inventory.setItem(PREVIEW, sh);
        inventory.setItem(APPLY_BASE, button(Material.WHITE_BANNER, "Set BASE colour to: " + selectedColorName(), NamedTextColor.GOLD));
        for (int i = 0; i < 16; i++) {
            ItemStack d = new ItemStack(DYES[i]);
            ItemMeta dm = d.getItemMeta();
            dm.displayName(plain(DyeColor.values()[i].name().toLowerCase(Locale.ROOT)
                    + (i == color ? "  (selected)" : ""), i == color ? NamedTextColor.GREEN : NamedTextColor.WHITE));
            if (i == color) {
                dm.setEnchantmentGlintOverride(true);
            }
            d.setItemMeta(dm);
            inventory.setItem(9 + i, d);
        }
        inventory.setItem(UNDO, button(Material.BARRIER, "Remove last layer", NamedTextColor.RED));
        inventory.setItem(CLEAR, button(Material.TNT, "Clear banner (back to default)", NamedTextColor.RED));
        inventory.setItem(1, info(Material.PAPER, "Pick a colour, set the base, then click a",
                "pattern below to add a layer in the selected colour.", "Max 6 layers."));
    }

    private void trimLayout() {
        for (int i = 0; i < 4; i++) {
            ItemStack a = bot.armor[i] == null ? new ItemStack(Material.BARRIER) : bot.armor[i].clone();
            ItemMeta am = a.getItemMeta();
            am.displayName(plain(Cosmetics.SLOTS[i] + (piece == i ? "  (selected)" : "")
                    + (bot.trims[i] == null ? "" : " [" + bot.trims[i] + "]"),
                    piece == i ? NamedTextColor.GREEN : NamedTextColor.WHITE));
            am.lore(List.of(plain(bot.armor[i] == null ? "No armor in this slot - give a kit first" : "Click to select",
                    NamedTextColor.GRAY)));
            a.setItemMeta(am);
            inventory.setItem(i == 0 ? 0 : i == 1 ? 1 : i == 2 ? 2 : 3, a);
        }
        inventory.setItem(PREVIEW, info(piece < 0 ? Material.NETHERITE_CHESTPLATE : Material.IRON_CHESTPLATE,
                piece < 0 ? "Editing: ALL pieces" : "Editing: " + Cosmetics.SLOTS[piece],
                "Click the same piece again for ALL", "1) pick a material  2) click a pattern"));
        for (int i = 0; i < materials.size() && i < 16; i++) {
            Material mat = materialIcon(materials.get(i));
            ItemStack it = new ItemStack(mat);
            ItemMeta im = it.getItemMeta();
            im.displayName(plain(materials.get(i) + (i == material ? "  (selected)" : ""),
                    i == material ? NamedTextColor.GREEN : NamedTextColor.WHITE));
            if (i == material) {
                im.setEnchantmentGlintOverride(true);
            }
            it.setItemMeta(im);
            inventory.setItem(9 + i, it);
        }
        inventory.setItem(UNDO, button(Material.BARRIER, "Remove trim from selection", NamedTextColor.RED));
        inventory.setItem(CLEAR, button(Material.TNT, "Remove ALL trims", NamedTextColor.RED));
    }

    private static Material materialIcon(String n) {
        Material m = Material.matchMaterial(switch (n) {
            case "amethyst" -> "AMETHYST_SHARD";
            case "lapis" -> "LAPIS_LAZULI";
            case "redstone" -> "REDSTONE";
            case "quartz" -> "QUARTZ";
            case "resin" -> "RESIN_BRICK";
            default -> n.toUpperCase(Locale.ROOT) + "_INGOT";
        });
        if (m == null) {
            m = Material.matchMaterial(n.toUpperCase(Locale.ROOT));
        }
        return m == null ? Material.PAPER : m;
    }

    private ItemStack patternItem(String p) {
        ItemStack it;
        if (mode == Mode.SHIELD) {
            it = new ItemStack(Material.WHITE_BANNER);
            if (it.getItemMeta() instanceof org.bukkit.inventory.meta.BannerMeta bm) {
                org.bukkit.block.banner.PatternType pt = org.bukkit.Registry.BANNER_PATTERN.get(Cosmetics.key(p));
                if (pt != null) {
                    bm.addPattern(new org.bukkit.block.banner.Pattern(DyeColor.values()[color], pt));
                }
                it.setItemMeta(bm);
            }
        } else {
            Material t = Material.matchMaterial(p.toUpperCase(Locale.ROOT) + "_ARMOR_TRIM_SMITHING_TEMPLATE");
            it = new ItemStack(t == null ? Material.PAPER : t);
        }
        ItemMeta m = it.getItemMeta();
        m.displayName(plain(p, NamedTextColor.YELLOW));
        m.lore(List.of(plain("Click to " + (mode == Mode.SHIELD ? "add as a layer" : "apply"), NamedTextColor.GRAY)));
        m.addItemFlags(org.bukkit.inventory.ItemFlag.values());
        it.setItemMeta(m);
        return it;
    }

    private static ItemStack pane() {
        ItemStack item = new ItemStack(Material.BLACK_STAINED_GLASS_PANE);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(plain(" ", NamedTextColor.GRAY));
        item.setItemMeta(meta);
        return item;
    }

    private static ItemStack button(Material mat, String label, NamedTextColor c) {
        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(plain(label, c));
        item.setItemMeta(meta);
        return item;
    }

    private static ItemStack info(Material mat, String label, String... lore) {
        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(plain(label, NamedTextColor.YELLOW));
        List<Component> l = new ArrayList<>();
        for (String s : lore) {
            l.add(plain(s, NamedTextColor.GRAY));
        }
        meta.lore(l);
        item.setItemMeta(meta);
        return item;
    }

    private static Component plain(String s, NamedTextColor c) {
        return Component.text(s, c).decoration(TextDecoration.ITALIC, false);
    }
}
