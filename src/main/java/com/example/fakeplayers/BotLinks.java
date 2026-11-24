package com.example.fakeplayers;

import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Which bots a player controls with which item (/bot link). Kept in memory only. */
public class BotLinks {

    private record Link(UUID player, String bot, ItemStack item) {
    }

    private final List<Link> links = new ArrayList<>();

    /** Links a bot to the item the player holds. Returns an error message or null. */
    public String link(Player p, Bot b) {
        ItemStack held = p.getInventory().getItemInMainHand();
        if (Bot.empty(held)) {
            return "Hold the item you want to link first.";
        }
        if (held.getType().isBlock()) {
            return "Blocks can't be linked. Hold a non-block item.";
        }
        ItemStack key = held.clone();
        key.setAmount(1);
        links.removeIf(l -> l.player.equals(p.getUniqueId()) && l.bot.equalsIgnoreCase(b.name));
        links.add(new Link(p.getUniqueId(), b.name, key));
        return null;
    }

    public int unlink(Player p, Bot b) {
        int before = links.size();
        links.removeIf(l -> l.player.equals(p.getUniqueId()) && (b == null || l.bot.equalsIgnoreCase(b.name)));
        return before - links.size();
    }

    public List<Bot> botsFor(Player p, ItemStack held, BotManager mgr) {
        List<Bot> out = new ArrayList<>();
        if (Bot.empty(held)) {
            return out;
        }
        for (Link l : links) {
            if (l.player.equals(p.getUniqueId()) && l.item.isSimilar(held)) {
                Bot b = mgr.get(l.bot);
                if (b != null && mgr.entity(b) != null) {
                    out.add(b);
                }
            }
        }
        return out;
    }
}
