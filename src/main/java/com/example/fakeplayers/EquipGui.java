package com.example.fakeplayers;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Mannequin;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

/**
 * Equipment screen for one bot. Click an item in your own inventory, then click a bot slot
 * to place it (or shift-click an item in your inventory to auto-equip it). Click a filled
 * bot slot with an empty cursor to take the item back.
 */
public class EquipGui implements InventoryHolder {

    /** Set once by the plugin on enable. Marks the grey placeholder panes. */
    public static NamespacedKey KEY;

    public static final int HEAD = 10;
    public static final int CHEST = 11;
    public static final int LEGS = 12;
    public static final int FEET = 13;
    public static final int MAIN = 15;
    public static final int OFF = 16;
    public static final int BACK = 18;
    public static final int CLOSE = 26;

    private final Bot bot;
    private final Inventory inventory;

    public EquipGui(Bot bot, Mannequin m) {
        this.bot = bot;
        this.inventory = Bukkit.createInventory(this, 27, Component.text("Equipment: " + bot.name));
        ItemStack filler = pane(Material.BLACK_STAINED_GLASS_PANE, " ");
        for (int i = 0; i < 27; i++) {
            inventory.setItem(i, filler.clone());
        }
        EntityEquipment eq = m.getEquipment();
        set(HEAD, eq.getHelmet());
        set(CHEST, eq.getChestplate());
        set(LEGS, eq.getLeggings());
        set(FEET, eq.getBoots());
        set(MAIN, eq.getItemInMainHand());
        set(OFF, eq.getItemInOffHand());
        inventory.setItem(BACK, button(Material.ARROW, "Back", NamedTextColor.YELLOW));
        inventory.setItem(CLOSE, button(Material.OAK_DOOR, "Close", NamedTextColor.GRAY));
    }

    public Bot bot() {
        return bot;
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    public static boolean isEquipSlot(int slot) {
        return slot == HEAD || slot == CHEST || slot == LEGS || slot == FEET || slot == MAIN || slot == OFF;
    }

    /** Which slot an item should go to when shift-clicked. */
    public static int targetSlot(Material mat) {
        String n = mat.name();
        if (n.endsWith("_HELMET")) {
            return HEAD;
        }
        if (n.endsWith("_CHESTPLATE") || n.equals("ELYTRA")) {
            return CHEST;
        }
        if (n.endsWith("_LEGGINGS")) {
            return LEGS;
        }
        if (n.endsWith("_BOOTS")) {
            return FEET;
        }
        if (n.equals("SHIELD") || n.equals("TOTEM_OF_UNDYING")) {
            return OFF;
        }
        return MAIN;
    }

    /** The real item in an equipment slot, or null if it only holds a placeholder. */
    public ItemStack real(int slot) {
        ItemStack i = inventory.getItem(slot);
        if (i == null || i.getType().isAir() || isPlaceholder(i)) {
            return null;
        }
        return i;
    }

    /** Puts an item in a slot, or the grey placeholder when item is null/air. */
    public void set(int slot, ItemStack item) {
        if (item == null || item.getType().isAir()) {
            inventory.setItem(slot, pane(Material.LIGHT_GRAY_STAINED_GLASS_PANE, label(slot) + " (empty)"));
        } else {
            inventory.setItem(slot, item.clone());
        }
    }

    /** Writes the screen's contents onto the bot. */
    public void save(Mannequin m) {
        EntityEquipment eq = m.getEquipment();
        eq.setHelmet(real(HEAD));
        eq.setChestplate(real(CHEST));
        eq.setLeggings(real(LEGS));
        eq.setBoots(real(FEET));
        eq.setItemInMainHand(real(MAIN));
        eq.setItemInOffHand(real(OFF));
    }

    private static String label(int slot) {
        return switch (slot) {
            case HEAD -> "Head";
            case CHEST -> "Chest";
            case LEGS -> "Legs";
            case FEET -> "Feet";
            case MAIN -> "Main hand";
            case OFF -> "Off hand";
            default -> "Slot";
        };
    }

    private static boolean isPlaceholder(ItemStack item) {
        if (KEY == null || !item.hasItemMeta()) {
            return false;
        }
        return item.getItemMeta().getPersistentDataContainer().has(KEY, PersistentDataType.STRING);
    }

    private static ItemStack pane(Material mat, String name) {
        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(name, NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
        if (KEY != null) {
            meta.getPersistentDataContainer().set(KEY, PersistentDataType.STRING, "placeholder");
        }
        item.setItemMeta(meta);
        return item;
    }

    private static ItemStack button(Material mat, String name, NamedTextColor color) {
        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(name, color).decoration(TextDecoration.ITALIC, false));
        if (KEY != null) {
            meta.getPersistentDataContainer().set(KEY, PersistentDataType.STRING, "button");
        }
        item.setItemMeta(meta);
        return item;
    }
}
