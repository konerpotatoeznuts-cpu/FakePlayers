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

import java.util.ArrayList;
import java.util.List;

/** Paged list of every PvP option. Click to change; right-click or shift-click for more control. */
public class PvpGui implements InventoryHolder {

    public static final int PER_PAGE = 45;
    public static final int PREV = 45;
    public static final int PRESET = 46;
    public static final int PLATFORM = 47;
    public static final int KIT = 48;
    public static final int BACK = 49;
    public static final int COMBAT = 50;
    public static final int RESET = 52;
    public static final int NEXT = 53;

    private final FakePlayersPlugin plugin;
    private final Bot bot;
    private final Inventory inventory;
    private int page;

    public PvpGui(FakePlayersPlugin plugin, Bot bot, int page) {
        this.plugin = plugin;
        this.bot = bot;
        this.page = page;
        this.inventory = Bukkit.createInventory(this, 54, Component.text("PvP: " + bot.name));
        refresh();
    }

    public Bot bot() {
        return bot;
    }

    public int page() {
        return page;
    }

    public void setPage(int p) {
        int max = (PvpOptions.ALL.size() - 1) / PER_PAGE;
        this.page = Math.max(0, Math.min(max, p));
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    public void refresh() {
        inventory.clear();
        int start = page * PER_PAGE;
        for (int i = 0; i < PER_PAGE && start + i < PvpOptions.ALL.size(); i++) {
            inventory.setItem(i, optionItem(PvpOptions.ALL.get(start + i)));
        }
        inventory.setItem(PREV, simple(Material.ARROW, "Previous page", NamedTextColor.YELLOW));
        inventory.setItem(PRESET, lore(Material.NAME_TAG, "Preset: " + bot.preset, NamedTextColor.YELLOW,
                "Click: next preset, right-click: previous", "Clears custom values"));
        inventory.setItem(PLATFORM, lore(Material.COMPASS, "Platform: " + bot.platform.name(), NamedTextColor.YELLOW,
                "Click to switch Java / Bedrock"));
        inventory.setItem(KIT, lore(Material.SHULKER_BOX, "Give kit: " + bot.preset, NamedTextColor.YELLOW,
                "Replaces the inventory with the preset kit"));
        inventory.setItem(BACK, simple(Material.OAK_DOOR, "Back", NamedTextColor.GRAY));
        inventory.setItem(COMBAT, lore(Material.DIAMOND_SWORD, "Combat: " + (bot.combat ? "ON" : "OFF"),
                bot.combat ? NamedTextColor.GREEN : NamedTextColor.RED, "Click to toggle"));
        inventory.setItem(RESET, lore(Material.TNT, "Reset custom values", NamedTextColor.RED,
                "Back to preset + config defaults"));
        inventory.setItem(NEXT, simple(Material.ARROW, "Next page (" + (page + 1) + "/"
                + ((PvpOptions.ALL.size() - 1) / PER_PAGE + 1) + ")", NamedTextColor.YELLOW));
    }

    private ItemStack optionItem(PvpOptions.Opt o) {
        String cur = PvpOptions.raw(plugin, bot, o.path());
        boolean custom = bot.pvp.containsKey(o.path());
        ItemStack item = new ItemStack(o.icon());
        ItemMeta meta = item.getItemMeta();
        NamedTextColor nameColor = NamedTextColor.WHITE;
        if (o.type() == PvpOptions.Type.BOOL) {
            nameColor = Boolean.parseBoolean(cur) ? NamedTextColor.GREEN : NamedTextColor.RED;
        }
        meta.displayName(Component.text(o.path(), nameColor).decoration(TextDecoration.ITALIC, false));
        List<Component> lines = new ArrayList<>();
        lines.add(plain(o.desc(), NamedTextColor.GRAY));
        lines.add(plain("Value: " + (cur.isEmpty() ? "(empty)" : cur) + (custom ? "  (custom)" : ""), NamedTextColor.YELLOW));
        switch (o.type()) {
            case BOOL -> lines.add(plain("Click to toggle", NamedTextColor.DARK_GRAY));
            case INT, DOUBLE -> lines.add(plain("Left +" + fmt(o.step()) + ", right -" + fmt(o.step())
                    + ", shift x5", NamedTextColor.DARK_GRAY));
            case CHOICE -> lines.add(plain("Left next, right previous: " + String.join(", ", o.choices()),
                    NamedTextColor.DARK_GRAY));
            default -> lines.add(plain("Change with /bot pvp " + bot.name + " set " + o.path() + " <text>",
                    NamedTextColor.DARK_GRAY));
        }
        meta.lore(lines);
        item.setItemMeta(meta);
        return item;
    }

    private static String fmt(double d) {
        return d == Math.rint(d) ? String.valueOf((int) d) : String.valueOf(d);
    }

    private static Component plain(String s, NamedTextColor c) {
        return Component.text(s, c).decoration(TextDecoration.ITALIC, false);
    }

    private static ItemStack simple(Material mat, String name, NamedTextColor c) {
        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(plain(name, c));
        item.setItemMeta(meta);
        return item;
    }

    private static ItemStack lore(Material mat, String name, NamedTextColor c, String... lore) {
        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(plain(name, c));
        List<Component> lines = new ArrayList<>();
        for (String l : lore) {
            lines.add(plain(l, NamedTextColor.GRAY));
        }
        meta.lore(lines);
        item.setItemMeta(meta);
        return item;
    }
}
