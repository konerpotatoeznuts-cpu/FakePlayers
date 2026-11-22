package com.example.fakeplayers;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mannequin;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityResurrectEvent;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.util.Vector;

import java.util.List;

public class BotListener implements Listener {

    private final FakePlayersPlugin plugin;

    public BotListener(FakePlayersPlugin plugin) {
        this.plugin = plugin;
    }

    private BotManager mgr() {
        return plugin.manager();
    }

    // ------------------------------------------------------------ opening the menu

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

    // ------------------------------------------------------------ damage, shields, totems

    @EventHandler(ignoreCancelled = true)
    public void onDamage(EntityDamageEvent e) {
        Bot bot = mgr().botOf(e.getEntity());
        if (bot != null && bot.invulnerable) {
            e.setCancelled(true);
        }
    }

    /** Emulated shield: the mannequin cannot raise a real shield, so blocking is handled here. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onHitBot(EntityDamageByEntityEvent e) {
        Bot bot = mgr().botOf(e.getEntity());
        if (bot == null || !bot.st.shielding || !(e.getEntity() instanceof Mannequin m)) {
            return;
        }
        Entity attacker = e.getDamager();
        Vector look = m.getEyeLocation().getDirection().setY(0);
        Vector to = attacker.getLocation().toVector().subtract(m.getLocation().toVector()).setY(0);
        if (look.lengthSquared() < 1e-6 || to.lengthSquared() < 1e-6 || look.normalize().dot(to.normalize()) <= 0) {
            return;
        }
        Material weapon = Material.AIR;
        if (attacker instanceof LivingEntity le) {
            EntityEquipment eq = le.getEquipment();
            if (eq != null) {
                weapon = eq.getItemInMainHand().getType();
            }
        }
        if (weapon.name().endsWith("_AXE")) {
            bot.st.shieldDisabled = Math.max(1, PvpOptions.integer(plugin, bot, "shield.axe-disable-ticks"));
            bot.st.shielding = false;
            m.getWorld().playSound(m.getLocation(), Sound.ITEM_SHIELD_BREAK, 1f, 1f);
            return;
        }
        e.setCancelled(true);
        m.getWorld().playSound(m.getLocation(), Sound.ITEM_SHIELD_BLOCK, 1f, 1f);
        ItemStack shield = bot.offhand != null && bot.offhand.getType() == Material.SHIELD ? bot.offhand : bot.held();
        if (shield != null && shield.getType() == Material.SHIELD && shield.getItemMeta() instanceof Damageable dm) {
            dm.setDamage(dm.getDamage() + 1 + (int) Math.floor(e.getDamage()));
            shield.setItemMeta(dm);
            if (dm.getDamage() >= Material.SHIELD.getMaxDurability()) {
                if (shield == bot.offhand) {
                    bot.offhand = null;
                } else {
                    bot.inv[bot.selected] = null;
                }
                bot.st.shielding = false;
                m.getWorld().playSound(m.getLocation(), Sound.ITEM_SHIELD_BREAK, 1f, 1f);
            }
            bot.equipDirty = true;
        }
    }

    @EventHandler
    public void onResurrect(EntityResurrectEvent e) {
        Bot bot = mgr().botOf(e.getEntity());
        if (bot == null) {
            return;
        }
        Bukkit.getScheduler().runTask(plugin, () -> {
            Mannequin m = mgr().entity(bot);
            if (m != null) {
                mgr().combat().afterTotem(bot, m);
            }
        });
    }

    @EventHandler
    public void onDeath(EntityDeathEvent e) {
        Entity dead = e.getEntity();

        // A bot died
        Bot bot = mgr().botOf(dead);
        if (bot != null) {
            e.getDrops().clear();
            e.setDroppedExp(0);
            if (plugin.getConfig().getBoolean("death.drop-items", false)) {
                Location loc = dead.getLocation();
                for (ItemStack it : bot.inv) {
                    if (!Bot.empty(it)) {
                        loc.getWorld().dropItemNaturally(loc, it);
                    }
                }
                for (ItemStack it : bot.armor) {
                    if (!Bot.empty(it)) {
                        loc.getWorld().dropItemNaturally(loc, it);
                    }
                }
                if (!Bot.empty(bot.offhand)) {
                    loc.getWorld().dropItemNaturally(loc, bot.offhand);
                }
                java.util.Arrays.fill(bot.inv, null);
                java.util.Arrays.fill(bot.armor, null);
                bot.offhand = null;
            }
            Player killer = e.getEntity().getKiller();
            if (killer != null) {
                mgr().runCommands("integration.player-kills-bot",
                        mgr().placeholders(bot, (Mannequin) dead, "player", killer.getName()));
            }
            bot.resetCombat();
            bot.equipDirty = true;
            int delay = plugin.getConfig().getInt("death.respawn-delay-ticks", 100);
            if (delay >= 0) {
                mgr().respawnLater(bot, delay);
            } else {
                mgr().remove(bot);
            }
            mgr().save();
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

    /** Lets bots react to water or lava a player places (water draining). */
    @EventHandler(ignoreCancelled = true)
    public void onBucket(PlayerBucketEmptyEvent e) {
        mgr().combat().onFluidPlaced(e.getPlayer(), e.getBlock(), e.getBucket());
    }

    // ------------------------------------------------------------ main bot menu

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        Inventory top = e.getView().getTopInventory();
        if (top.getHolder() instanceof InvGui gui) {
            onInvClick(e, gui, top);
            return;
        }
        if (top.getHolder() instanceof PvpGui gui) {
            onPvpClick(e, gui, top);
            return;
        }
        if (!(top.getHolder() instanceof BotGui gui)) {
            return;
        }
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p) || !p.isOp()) {
            return;
        }
        if (e.getClickedInventory() == null || e.getClickedInventory() != top) {
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
            case BotGui.SLOT_COMBAT -> {
                b.combat = !b.combat;
                b.resetCombat();
            }
            case BotGui.SLOT_FOLLOW_OP -> b.followNearestOp = !b.followNearestOp;
            case BotGui.SLOT_AUTO_CHAT -> b.autoChat = !b.autoChat;
            case BotGui.SLOT_LOOP -> b.loop = !b.loop;
            case BotGui.SLOT_PLATFORM -> {
                b.platform = b.platform == Bot.Platform.JAVA ? Bot.Platform.BEDROCK : Bot.Platform.JAVA;
                if (m != null) {
                    mgr().apply(b, m);
                }
            }
            case BotGui.SLOT_INVENTORY -> {
                Bukkit.getScheduler().runTask(plugin, () -> p.openInventory(new InvGui(b).getInventory()));
                return;
            }
            case BotGui.SLOT_PVP -> {
                Bukkit.getScheduler().runTask(plugin, () -> p.openInventory(new PvpGui(plugin, b, 0).getInventory()));
                return;
            }
            case BotGui.SLOT_PRESET -> cyclePreset(b, e.isRightClick() ? -1 : 1);
            case BotGui.SLOT_KIT -> {
                String err = Kits.apply(plugin, b, b.preset);
                if (err != null) {
                    p.sendMessage(plugin.parse("<red>" + err));
                }
            }
            case BotGui.SLOT_TP_HERE -> {
                if (m != null) {
                    m.teleport(p.getLocation());
                }
            }
            case BotGui.SLOT_SET_HOME -> b.home = p.getLocation().clone();
            case BotGui.SLOT_USE -> mgr().runCommands("integration.on-use", mgr().placeholders(b, m, "victim", ""));
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

    private void cyclePreset(Bot b, int dir) {
        List<String> presets = plugin.presets();
        if (presets.isEmpty()) {
            return;
        }
        int idx = Math.max(0, presets.indexOf(b.preset));
        b.preset = presets.get(((idx + dir) % presets.size() + presets.size()) % presets.size());
        b.pvp.clear();
    }

    // ------------------------------------------------------------ PvP menu

    private void onPvpClick(InventoryClickEvent e, PvpGui gui, Inventory top) {
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p) || !p.isOp()) {
            return;
        }
        if (e.getClickedInventory() == null || e.getClickedInventory() != top) {
            return;
        }
        Bot b = gui.bot();
        int slot = e.getSlot();
        if (slot < PvpGui.PER_PAGE) {
            int idx = gui.page() * PvpGui.PER_PAGE + slot;
            if (idx >= PvpOptions.ALL.size()) {
                return;
            }
            PvpOptions.Opt opt = PvpOptions.ALL.get(idx);
            String cur = PvpOptions.raw(plugin, b, opt.path());
            String next = PvpOptions.step(opt, cur, e.isRightClick() ? -1 : 1, e.isShiftClick() ? 5 : 1);
            if (next == null) {
                p.sendMessage(plugin.parse("<yellow>Use <white>/bot pvp " + b.name + " set " + opt.path() + " <text></white>"));
                return;
            }
            b.pvp.put(opt.path(), next);
        } else {
            switch (slot) {
                case PvpGui.PREV -> gui.setPage(gui.page() - 1);
                case PvpGui.NEXT -> gui.setPage(gui.page() + 1);
                case PvpGui.PRESET -> cyclePreset(b, e.isRightClick() ? -1 : 1);
                case PvpGui.PLATFORM -> {
                    b.platform = b.platform == Bot.Platform.JAVA ? Bot.Platform.BEDROCK : Bot.Platform.JAVA;
                    Mannequin m = mgr().entity(b);
                    if (m != null) {
                        mgr().apply(b, m);
                    }
                }
                case PvpGui.KIT -> {
                    String err = Kits.apply(plugin, b, b.preset);
                    if (err != null) {
                        p.sendMessage(plugin.parse("<red>" + err));
                    }
                }
                case PvpGui.COMBAT -> {
                    b.combat = !b.combat;
                    b.resetCombat();
                }
                case PvpGui.RESET -> b.pvp.clear();
                case PvpGui.BACK -> {
                    Bukkit.getScheduler().runTask(plugin, () -> p.openInventory(new BotGui(b).getInventory()));
                    return;
                }
                default -> {
                    return;
                }
            }
        }
        mgr().save();
        gui.refresh();
    }

    // ------------------------------------------------------------ inventory menu

    private void onInvClick(InventoryClickEvent e, InvGui gui, Inventory top) {
        if (!(e.getWhoClicked() instanceof Player p) || !p.isOp()) {
            e.setCancelled(true);
            return;
        }
        if (e.getAction() == InventoryAction.COLLECT_TO_CURSOR) {
            e.setCancelled(true);
            return;
        }
        if (e.getClickedInventory() == top) {
            int s = e.getSlot();
            if (s == InvGui.BACK) {
                e.setCancelled(true);
                gui.saveToBot();
                mgr().save();
                Bukkit.getScheduler().runTask(plugin, () -> p.openInventory(new BotGui(gui.bot()).getInventory()));
                return;
            }
            if (s == InvGui.CLOSE) {
                e.setCancelled(true);
                p.closeInventory();
                return;
            }
            if (s == InvGui.HELD) {
                e.setCancelled(true);
                gui.saveToBot();
                gui.bot().selected = (gui.bot().selected + 1) % 9;
                gui.bot().equipDirty = true;
                gui.refreshHeld();
                mgr().save();
                return;
            }
            if (!InvGui.editable(s)) {
                e.setCancelled(true);
                return;
            }
        }
        // the click is allowed; copy the result to the bot after the game has applied it
        Bukkit.getScheduler().runTask(plugin, () -> {
            gui.saveToBot();
            mgr().save();
        });
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        Inventory top = e.getView().getTopInventory();
        if (top.getHolder() instanceof BotGui || top.getHolder() instanceof PvpGui) {
            e.setCancelled(true);
            return;
        }
        if (top.getHolder() instanceof InvGui gui) {
            for (int raw : e.getRawSlots()) {
                if (raw < top.getSize() && !InvGui.editable(raw)) {
                    e.setCancelled(true);
                    return;
                }
            }
            Bukkit.getScheduler().runTask(plugin, () -> {
                gui.saveToBot();
                mgr().save();
            });
        }
    }

    @EventHandler
    public void onClose(InventoryCloseEvent e) {
        if (e.getInventory().getHolder() instanceof InvGui gui) {
            gui.saveToBot();
            mgr().save();
        }
    }
}
