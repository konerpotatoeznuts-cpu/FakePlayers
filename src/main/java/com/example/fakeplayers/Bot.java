package com.example.fakeplayers;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

/** Settings, inventory and runtime state of one fake player. */
public class Bot {

    public enum Platform { JAVA, BEDROCK }

    /** Runtime-only combat state. */
    public static class CombatState {
        public UUID targetId;
        public int reaction;
        public int attackTimer;
        public int strafeDir = 1;
        public int strafeTimer;
        public int acquireTimer;
        public int jumpedTicks;
        public int eatTicks;
        public int eatSlot = -1;
        public String eatKind = "";
        public int prevSelected = -1;
        public boolean shielding;
        public int shieldTimer;
        public int shieldDisabled;
        public int drawTicks;
        public int pcrit;
        public Location lastLoc;
        public UUID pearlId;
        public String pearlPlan = "";
        public int windIn;
        public int pearlAge;
        public int flight;
        public int flightTicks;
        public double peakY;
        public int stunWindow;
        public int noSelect;
        public int baseSlot = -1;
        public int swapBackTimer;
        public boolean restoreChest;
        public boolean shieldRaised;
        public int webTicks;
        public boolean smArming;
        public double vy;
        public int totemWait;
        public double dirX;
        public double dirZ;
        public double speedFactor;
        public final Set<UUID> crystals = new HashSet<>();
        public final Map<String, Integer> cd = new HashMap<>();

        public boolean ready(String key) {
            return cd.getOrDefault(key, 0) <= 0;
        }

        public void setCd(String key, int ticks) {
            cd.put(key, Math.max(0, ticks));
        }
    }

    public final String name;
    public UUID entityId;
    public String skin;
    public Location home;

    // saved settings
    public boolean immovable = false;
    public boolean invulnerable = false;
    public boolean dropItems = true;
    public boolean leaveOnDeath = false;
    public String lookAt = null;
    public List<String> patrol = new ArrayList<>();
    public int patrolIdx = 0;
    public boolean patrolActive = false;
    public Map<String, String> replies = new LinkedHashMap<>();
    public String greet = null;
    public final Map<UUID, Long> greeted = new HashMap<>();
    public long lastReply = -1000;
    public int rejoinSeconds = 10;
    public boolean combat = false;
    public boolean followNearestOp = false;
    public String followTarget = null;
    public String forcedTarget = null;
    public boolean autoChat = false;
    public boolean loop = false;
    public double speed = 0.2;
    public int chatIntervalSeconds = 60;
    public List<String> chatLines = new ArrayList<>();
    public Deque<String> queue = new ArrayDeque<>();
    public Platform platform = Platform.JAVA;
    public String preset = "sword";
    public String lastKit = null;
    public Map<String, String> pvp = new LinkedHashMap<>();

    // saved inventory: 0-8 hotbar, 9-35 main
    public ItemStack[] inv = new ItemStack[36];
    public ItemStack[] armor = new ItemStack[4]; // head, chest, legs, feet
    public ItemStack offhand;
    public int selected = 0;
    public boolean equipDirty = true;

    // runtime
    public Location walkTarget = null;
    public Location navGoal = null;
    public int navTicks = 0;
    public int navStuck = 0;
    public Location navLast = null;
    public int navDig = -1;
    public Location navDigAt = null;
    public UUID clickTarget = null;
    public boolean combatBefore = false;
    public boolean stealth = false;
    public boolean stealthOn = false;
    public int walkTicks = 0;
    public int waitTicks = 0;
    public int chatCountdown = 0;
    public CombatState st = new CombatState();

    public Bot(String name) {
        this.name = name;
    }

    public String displayName(String bedrockPrefix) {
        return platform == Platform.BEDROCK ? bedrockPrefix + name : name;
    }

    // ------------------------------------------------------------ inventory helpers

    public static boolean empty(ItemStack i) {
        return i == null || i.getType().isAir() || i.getAmount() <= 0;
    }

    public ItemStack held() {
        return empty(inv[selected]) ? null : inv[selected];
    }

    /** Slot index (0-35) of the first matching item, hotbar first; -1 if none. */
    public int findIf(Predicate<ItemStack> p, boolean hotbarOnly) {
        int max = hotbarOnly ? 9 : 36;
        for (int i = 0; i < max; i++) {
            if (!empty(inv[i]) && p.test(inv[i])) {
                return i;
            }
        }
        return -1;
    }

    public int find(Material mat) {
        return findIf(i -> i.getType() == mat, false);
    }

    public void take(int slot, int amount) {
        ItemStack s = inv[slot];
        if (s == null) {
            return;
        }
        if (s.getAmount() <= amount) {
            inv[slot] = null;
        } else {
            s.setAmount(s.getAmount() - amount);
        }
        equipDirty = true;
    }

    /** Adds an item to the inventory; returns what did not fit (or null). */
    public ItemStack give(ItemStack item) {
        ItemStack rest = item.clone();
        for (int i = 0; i < 36 && rest.getAmount() > 0; i++) {
            ItemStack s = inv[i];
            if (!empty(s) && s.isSimilar(rest) && s.getAmount() < s.getMaxStackSize()) {
                int add = Math.min(s.getMaxStackSize() - s.getAmount(), rest.getAmount());
                s.setAmount(s.getAmount() + add);
                rest.setAmount(rest.getAmount() - add);
            }
        }
        for (int i = 0; i < 36 && rest.getAmount() > 0; i++) {
            if (empty(inv[i])) {
                int add = Math.min(rest.getMaxStackSize(), rest.getAmount());
                ItemStack put = rest.clone();
                put.setAmount(add);
                inv[i] = put;
                rest.setAmount(rest.getAmount() - add);
            }
        }
        equipDirty = true;
        return rest.getAmount() > 0 ? rest : null;
    }

    /** Uses up one item from a slot and puts a different item in the inventory (bucket fill, empty bottle). */
    public void convertOne(int slot, Material to) {
        take(slot, 1);
        give(new ItemStack(to));
    }

    public void resetCombat() {
        st = new CombatState();
    }
}
