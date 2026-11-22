package com.example.fakeplayers;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Mannequin;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

public class BotListener implements Listener {

    private final FakePlayersPlugin plugin;

    public BotListener(FakePlayersPlugin plugin) {
        this.plugin = plugin;
    }

    private BotManager mgr() {
        return plugin.manager();
    }

    /** Right-click a bot while OP + creative to open its config GUI. */
    @EventHandler(priority = EventPriority.HIGH)
    public void onInteract(PlayerInteractEntityEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) {
            return;
        }
        Bot bot = mgr().botOf(e.getRightClicked());
        if (bot == null) {
            return;
        }
        Player p = e.getPlayer();
        if (!p.isOp() || !p.hasPermission("fakeplayers.admin") || p.getGameMode() != GameMode.CREATIVE) {
            return;
        }
        e.setCancelled(true);
        p.openInventory(new BotGui(bot).getInventory());
    }

    @EventHandler(ignoreCancelled = true)
    public void onDamage(EntityDamageEvent e) {
        Bot bot = mgr().botOf(e.getEntity());
        if (bot != null && bot.invulnerable) {
            e.setCancelled(true);
        }
    }

    @EventHandler
    public void onDeath(EntityDeathEvent e) {
        Entity dead = e.getEntity();

        // A bot died
        Bot bot = mgr().botOf(dead);
        if (bot != null) {
            if (!plugin.getConfig().getBoolean("death.drop-items", false)) {
                e.getDrops().clear();
                e.setDroppedExp(0);
            }
            Player killer = e.getEntity().getKiller();
            if (killer != null) {
                mgr().runCommands("integration.player-kills-bot",
                        mgr().placeholders(bot, (Mannequin) dead, "player", killer.getName()));
            }
            int delay = plugin.getConfig().getInt("death.respawn-delay-ticks", 100);
            if (delay >= 0) {
                mgr().respawnLater(bot, delay);
            } else {
                mgr().remove(bot);
            }
            return;
        }

        // A player was killed by a bot
        if (dead instanceof Player victim
                && victim.getLastDamageCause() instanceof EntityDamageByEntityEvent ev) {
            Bot killerBot = mgr().botOf(ev.getDamager());
            if (killerBot != null) {
                mgr().runCommands("integration.bot-kills-player",
                        mgr().placeholders(killerBot, (Mannequin) ev.getDamager(), "victim", victim.getName()));
            }
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        Inventory top = e.getView().getTopInventory();
        if (top.getHolder() instanceof BotGui) {
            e.setCancelled(true);
        } else if (top.getHolder() instanceof EquipGui) {
            // dragging inside your own inventory is fine, dragging into the bot screen is not
            for (int raw : e.getRawSlots()) {
                if (raw < top.getSize()) {
                    e.setCancelled(true);
                    return;
                }
            }
        }
    }

    /** Equipment screen: click-to-place, shift-click to auto-equip, click filled slot to take back. */
    @EventHandler
    public void onEquipClick(InventoryClickEvent e) {
        Inventory top = e.getView().getTopInventory();
        if (!(top.getHolder() instanceof EquipGui gui)) {
            return;
        }
        if (!(e.getWhoClicked() instanceof Player p) || !p.isOp()) {
            e.setCancelled(true);
            return;
        }
        Mannequin m = mgr().entity(gui.bot());
        InventoryAction action = e.getAction();
        boolean inTop = e.getClickedInventory() == top;

        if (!inTop) {
            // clicks in the player's own inventory behave normally, except ones that would touch the bot screen
            if (action == InventoryAction.MOVE_TO_OTHER_INVENTORY) {
                e.setCancelled(true);
                ItemStack item = e.getCurrentItem();
                if (m == null || item == null || item.getType().isAir()) {
                    return;
                }
                int target = EquipGui.targetSlot(item.getType());
                ItemStack old = gui.real(target);
                gui.set(target, item);
                e.setCurrentItem(old);
                gui.save(m);
            } else if (action == InventoryAction.COLLECT_TO_CURSOR) {
                e.setCancelled(true);
            }
            return;
        }

        e.setCancelled(true);
        int slot = e.getSlot();
        if (slot == EquipGui.BACK) {
            Bukkit.getScheduler().runTask(plugin, () -> p.openInventory(new BotGui(gui.bot()).getInventory()));
            return;
        }
        if (slot == EquipGui.CLOSE) {
            p.closeInventory();
            return;
        }
        if (!EquipGui.isEquipSlot(slot) || m == null) {
            return;
        }

        ItemStack current = gui.real(slot);
        ItemStack cursor = p.getItemOnCursor();
        boolean cursorEmpty = cursor == null || cursor.getType().isAir();

        if (action == InventoryAction.MOVE_TO_OTHER_INVENTORY) {
            if (current != null) {
                for (ItemStack left : p.getInventory().addItem(current).values()) {
                    p.getWorld().dropItemNaturally(p.getLocation(), left);
                }
                gui.set(slot, null);
            }
        } else if (cursorEmpty) {
            if (current != null) {
                p.setItemOnCursor(current);
                gui.set(slot, null);
            }
        } else {
            p.setItemOnCursor(current == null ? new ItemStack(Material.AIR) : current);
            gui.set(slot, cursor);
        }
        gui.save(m);
        p.updateInventory();
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getView().getTopInventory().getHolder() instanceof BotGui gui)) {
            return;
        }
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p) || !p.isOp()) {
            return;
        }
        if (e.getClickedInventory() == null || e.getClickedInventory() != e.getView().getTopInventory()) {
            return;
        }
        Bot b = gui.bot();
        Mannequin m = mgr().entity(b);

        switch (e.getSlot()) {
            case BotGui.SLOT_INVULNERABLE -> b.invulnerable = !b.invulnerable;
            case BotGui.SLOT_IMMOVABLE -> {
                b.immovable = !b.immovable;
                if (m != null) {
                    m.setImmovable(b.immovable);
                }
            }
            case BotGui.SLOT_ATTACK_PLAYERS -> b.attackPlayers = !b.attackPlayers;
            case BotGui.SLOT_ATTACK_HOSTILES -> b.attackHostiles = !b.attackHostiles;
            case BotGui.SLOT_FOLLOW_OP -> b.followNearestOp = !b.followNearestOp;
            case BotGui.SLOT_AUTO_CHAT -> b.autoChat = !b.autoChat;
            case BotGui.SLOT_LOOP -> b.loop = !b.loop;
            case BotGui.SLOT_EQUIP -> {
                if (m != null) {
                    Bukkit.getScheduler().runTask(plugin, () -> p.openInventory(new EquipGui(b, m).getInventory()));
                }
                return;
            }
            case BotGui.SLOT_TP_HERE -> {
                if (m != null) {
                    m.teleport(p.getLocation());
                }
            }
            case BotGui.SLOT_SET_HOME -> {
                Location l = p.getLocation();
                b.home = l.clone();
            }
            case BotGui.SLOT_USE -> {
                mgr().runCommands("integration.on-use", mgr().placeholders(b, m, "victim", ""));
            }
            case BotGui.SLOT_REMOVE -> {
                mgr().remove(b);
                p.closeInventory();
                plugin.msg(p, "removed", "name", b.name);
                return;
            }
            case BotGui.SLOT_CLOSE -> {
                p.closeInventory();
                return;
            }
            default -> {
                return;
            }
        }
        mgr().save();
        gui.refresh();
    }
}
