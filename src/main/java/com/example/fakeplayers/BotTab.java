package com.example.fakeplayers;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;

/**
 * Shows bots in the player list. Paper has no API for fake tab entries, so this sends the vanilla
 * player-info packets by reflection. If a Paper update changes them, it logs one warning and stops trying.
 */
public class BotTab {

    private final FakePlayersPlugin plugin;
    private boolean failed;

    public BotTab(FakePlayersPlugin plugin) {
        this.plugin = plugin;
    }

    public static UUID tabId(Bot b) {
        return UUID.nameUUIDFromBytes(("fakeplayers:" + b.name.toLowerCase()).getBytes());
    }

    private boolean enabled() {
        return !failed && plugin.getConfig().getBoolean("tablist.enabled", true);
    }

    private String tabName(Bot b) {
        String n = b.displayName(plugin.getConfig().getString("platform.bedrock-prefix", "."));
        return n.length() > 16 ? n.substring(0, 16) : n;
    }

    public void showAll(Bot b) {
        if (!enabled()) {
            return;
        }
        for (Player p : Bukkit.getOnlinePlayers()) {
            show(b, p);
        }
    }

    public void hideAll(Bot b) {
        if (!enabled()) {
            return;
        }
        for (Player p : Bukkit.getOnlinePlayers()) {
            hide(b, p);
        }
    }

    /** Sends every living bot to one player (used when they join). */
    public void sendAllTo(Player p) {
        for (Bot b : plugin.manager().all()) {
            if (plugin.manager().entity(b) != null) {
                show(b, p);
            }
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    public void show(Bot b, Player viewer) {
        if (!enabled()) {
            return;
        }
        try {
            Class<?> profileC = Class.forName("com.mojang.authlib.GameProfile");
            Object profile = profileC.getConstructor(UUID.class, String.class).newInstance(tabId(b), tabName(b));
            String base = "net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket";
            Class<?> pktC = Class.forName(base);
            Class<?> actionC = Class.forName(base + "$Action");
            Class<?> entryC = Class.forName(base + "$Entry");
            Class<?> gtC = Class.forName("net.minecraft.world.level.GameType");

            EnumSet actions = EnumSet.noneOf((Class<Enum>) actionC);
            for (String a : new String[]{"ADD_PLAYER", "UPDATE_LISTED", "UPDATE_LATENCY", "UPDATE_GAME_MODE"}) {
                try {
                    actions.add(Enum.valueOf((Class<Enum>) actionC, a));
                } catch (IllegalArgumentException ignored) {
                    // not in this version
                }
            }
            Object survival = Enum.valueOf((Class<Enum>) gtC, "SURVIVAL");

            Constructor<?> ec = null;
            for (Constructor<?> c : entryC.getDeclaredConstructors()) {
                if (ec == null || c.getParameterCount() > ec.getParameterCount()) {
                    ec = c;
                }
            }
            ec.setAccessible(true);
            Class<?>[] types = ec.getParameterTypes();
            Object[] args = new Object[types.length];
            boolean firstBool = true;
            for (int i = 0; i < types.length; i++) {
                Class<?> t = types[i];
                if (t == UUID.class) {
                    args[i] = tabId(b);
                } else if (t == profileC) {
                    args[i] = profile;
                } else if (t == boolean.class) {
                    args[i] = firstBool;
                    firstBool = false;
                } else if (t == int.class) {
                    args[i] = 0;
                } else if (t == gtC) {
                    args[i] = survival;
                } else {
                    args[i] = null;
                }
            }
            Object entry = ec.newInstance(args);
            List<Object> entries = new ArrayList<>();
            entries.add(entry);
            Constructor<?> pc = pktC.getDeclaredConstructor(EnumSet.class, List.class);
            pc.setAccessible(true);
            send(viewer, pc.newInstance(actions, entries));
        } catch (Throwable t) {
            fail(t);
        }
    }

    public void hide(Bot b, Player viewer) {
        if (!enabled()) {
            return;
        }
        try {
            Class<?> pktC = Class.forName("net.minecraft.network.protocol.game.ClientboundPlayerInfoRemovePacket");
            List<UUID> ids = new ArrayList<>();
            ids.add(tabId(b));
            send(viewer, pktC.getConstructor(List.class).newInstance(ids));
        } catch (Throwable t) {
            fail(t);
        }
    }

    private void send(Player viewer, Object packet) throws Exception {
        Object handle = viewer.getClass().getMethod("getHandle").invoke(viewer);
        Object conn = handle.getClass().getField("connection").get(handle);
        for (Method m : conn.getClass().getMethods()) {
            if (m.getName().equals("send") && m.getParameterCount() == 1
                    && m.getParameterTypes()[0].isAssignableFrom(packet.getClass())) {
                m.invoke(conn, packet);
                return;
            }
        }
        throw new NoSuchMethodException("send(Packet)");
    }

    private void fail(Throwable t) {
        if (!failed) {
            failed = true;
            plugin.getLogger().warning("Tab list entries for bots are not supported on this server build ("
                    + t + "). Set tablist.enabled: false to silence this.");
        }
    }
}
