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

/** Main config menu for one bot. Works for Bedrock players through Geyser. */
public class BotGui implements InventoryHolder {

    public static final int SLOT_INVULNERABLE = 10;
    public static final int SLOT_IMMOVABLE = 11;
    public static final int SLOT_COMBAT = 12;
    public static final int SLOT_FOLLOW_OP = 13;
    public static final int SLOT_AUTO_CHAT = 14;
    public static final int SLOT_LOOP = 15;
    public static final int SLOT_PLATFORM = 16;
    public static final int SLOT_INVENTORY = 19;
    public static final int SLOT_PVP = 20;
    public static final int SLOT_PRESET = 21;
    public static final int SLOT_KIT = 22;
    public static final int SLOT_TP_HERE = 23;
    public static final int SLOT_SET_HOME = 24;
    public static final int SLOT_USE = 25;
    public static final int SLOT_REMOVE = 28;
    public static final int SLOT_CLOSE = 34;

    private final Bot bot;
    private final Inventory inventory;

    public BotGui(Bot bot) {
        this.bot = bot;
        this.inventory = Bukkit.createInventory(this, 36, Component.text("Bot: " + bot.name));
        refresh();
    }

    public Bot bot() {
        return bot;
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    public void refresh() {
        inventory.clear();
        inventory.setItem(SLOT_INVULNERABLE, toggle(Material.SHIELD, "Invulnerable", bot.invulnerable));
        inventory.setItem(SLOT_IMMOVABLE, toggle(Material.ANVIL, "Anchored (can't be pushed or walk)", bot.immovable));
        inventory.setItem(SLOT_COMBAT, toggle(Material.DIAMOND_SWORD, "Combat enabled (PvP brain)", bot.combat));
        inventory.setItem(SLOT_FOLLOW_OP, toggle(Material.LEAD, "Follow nearest OP", bot.followNearestOp));
        inventory.setItem(SLOT_AUTO_CHAT, toggle(Material.WRITABLE_BOOK, "Auto chat", bot.autoChat));
        inventory.setItem(SLOT_LOOP, toggle(Material.REPEATER, "Loop action queue", bot.loop));
        inventory.setItem(SLOT_PLATFORM, info(Material.COMPASS, "Platform: " + bot.platform.name(),
                "Click to switch Java / Bedrock",
                "Java: cooldown-timed hits", "Bedrock: click-spam, name prefix"));
        inventory.setItem(SLOT_INVENTORY, button(Material.CHEST, "Inventory + hotbar + armor", NamedTextColor.GREEN));
        inventory.setItem(SLOT_PVP, button(Material.NETHERITE_SWORD, "PvP settings", NamedTextColor.RED));
        inventory.setItem(SLOT_PRESET, info(Material.NAME_TAG, "Style preset: " + bot.preset,
                "Click for next preset, right-click for previous", "Clears this bot's custom PvP values"));
        inventory.setItem(SLOT_KIT, info(Material.SHULKER_BOX, "Give kit: " + bot.preset,
                "Replaces the inventory with the kit", "named like the preset (config pvp.kits)"));
        inventory.setItem(SLOT_TP_HERE, button(Material.ENDER_PEARL, "Teleport bot to me", NamedTextColor.LIGHT_PURPLE));
        inventory.setItem(SLOT_SET_HOME, button(Material.RED_BED, "Set respawn point here", NamedTextColor.AQUA));
        inventory.setItem(SLOT_USE, button(Material.NETHER_STAR, "Run 'on-use' integration commands", NamedTextColor.GOLD));
        inventory.setItem(SLOT_REMOVE, button(Material.BARRIER, "Remove bot", NamedTextColor.RED));
        inventory.setItem(SLOT_CLOSE, button(Material.OAK_DOOR, "Close", NamedTextColor.GRAY));
    }

    private static ItemStack toggle(Material mat, String label, boolean on) {
        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(plain(label, NamedTextColor.WHITE));
        meta.lore(List.of(on ? plain("ON - click to turn off", NamedTextColor.GREEN)
                : plain("OFF - click to turn on", NamedTextColor.RED)));
        item.setItemMeta(meta);
        return item;
    }

    private static ItemStack info(Material mat, String label, String... lore) {
        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(plain(label, NamedTextColor.YELLOW));
        List<Component> lines = new java.util.ArrayList<>();
        for (String l : lore) {
            lines.add(plain(l, NamedTextColor.GRAY));
        }
        meta.lore(lines);
        item.setItemMeta(meta);
        return item;
    }

    private static ItemStack button(Material mat, String label, NamedTextColor color) {
        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(plain(label, color));
        item.setItemMeta(meta);
        return item;
    }

    private static Component plain(String s, NamedTextColor color) {
        return Component.text(s, color).decoration(TextDecoration.ITALIC, false);
    }
}
