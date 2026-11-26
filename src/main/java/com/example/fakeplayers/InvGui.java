package com.example.fakeplayers;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.List;

/**
 * The bot's real inventory. Top 3 rows = main inventory, 4th row = hotbar,
 * bottom row = helmet, chestplate, leggings, boots, off hand. Move items in and out like any chest.
 */
public class InvGui implements InventoryHolder {

    public static final int[] ARMOR = {45, 46, 47, 48};
    public static final int OFFHAND = 49;
    public static final int BACK = 50;
    public static final int HELD = 51;
    public static final int CLOSE = 53;

    private final Bot bot;
    private final Inventory inventory;

    public InvGui(Bot bot) {
        this.bot = bot;
        this.inventory = Bukkit.createInventory(this, 54, Component.text("Inventory: " + bot.name));
        reload();
    }

    public Bot bot() {
        return bot;
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    public static boolean editable(int slot) {
        return slot >= 0 && slot < 36 || slot >= 45 && slot <= 49;
    }

    private static int invIndex(int guiSlot) {
        return guiSlot < 27 ? guiSlot + 9 : guiSlot - 27;
    }

    /** Fills the screen from the bot's data. */
    public void reload() {
        inventory.clear();
        for (int g = 0; g < 36; g++) {
            inventory.setItem(g, Bot.empty(bot.inv[invIndex(g)]) ? null : bot.inv[invIndex(g)].clone());
        }
        for (int i = 0; i < 4; i++) {
            inventory.setItem(ARMOR[i], Bot.empty(bot.armor[i]) ? null : bot.armor[i].clone());
        }
        inventory.setItem(OFFHAND, Bot.empty(bot.offhand) ? null : bot.offhand.clone());

        ItemStack filler = pane(" ");
        for (int s = 36; s < 45; s++) {
            inventory.setItem(s, filler.clone());
        }
        inventory.setItem(36, pane("Hotbar is the row above"));
        inventory.setItem(40, pane("Armor + off hand below"));
        inventory.setItem(44, pane("Slots below: head, chest, legs, feet, off hand"));
        inventory.setItem(52, filler.clone());
        inventory.setItem(BACK, button(Material.ARROW, "Back", NamedTextColor.YELLOW));
        inventory.setItem(CLOSE, button(Material.OAK_DOOR, "Close", NamedTextColor.GRAY));
        refreshHeld();
    }

    public void refreshHeld() {
        ItemStack held = button(Material.PAPER, "Held hotbar slot: " + (bot.selected + 1) + " (click to change)",
                NamedTextColor.GREEN);
        inventory.setItem(HELD, held);
    }

    /** Writes the screen back to the bot. */
    public void saveToBot() {
        for (int g = 0; g < 36; g++) {
            ItemStack it = inventory.getItem(g);
            bot.inv[invIndex(g)] = Bot.empty(it) ? null : it.clone();
        }
        for (int i = 0; i < 4; i++) {
            ItemStack it = inventory.getItem(ARMOR[i]);
            bot.armor[i] = Bot.empty(it) ? null : it.clone();
        }
        ItemStack off = inventory.getItem(OFFHAND);
        bot.offhand = Bot.empty(off) ? null : off.clone();
        Cosmetics.apply(bot);
        bot.equipDirty = true;
    }

    private static ItemStack pane(String name) {
        ItemStack item = new ItemStack(Material.BLACK_STAINED_GLASS_PANE);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(name, NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
        item.setItemMeta(meta);
        return item;
    }

    private static ItemStack button(Material mat, String name, NamedTextColor color) {
        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(name, color).decoration(TextDecoration.ITALIC, false));
        meta.lore(List.of(Component.text("OP only", NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC, false)));
        item.setItemMeta(meta);
        return item;
    }
}
