package com.example.fakeplayers;

import org.bukkit.Location;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.UUID;

/** Settings and runtime state of one fake player. */
public class Bot {

    public final String name;
    public UUID entityId;
    public String skin;
    public Location home;

    // settings (saved)
    public boolean immovable = false;
    public boolean invulnerable = false;
    public boolean attackPlayers = false;
    public boolean attackHostiles = false;
    public boolean followNearestOp = false;
    public String followTarget = null;
    public boolean autoChat = false;
    public boolean loop = false;
    public double speed = 0.2;
    public double damage = 4.0;
    public int chatIntervalSeconds = 60;
    public List<String> chatLines = new ArrayList<>();
    public Deque<String> queue = new ArrayDeque<>();

    // runtime (not saved)
    public Location walkTarget = null;
    public int walkTicks = 0;
    public int waitTicks = 0;
    public int attackCooldown = 0;
    public int chatCountdown = 0;

    public Bot(String name) {
        this.name = name;
    }
}
