package com.example.fakeplayers;

import org.bukkit.Location;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Named waypoints (waypoints.yml) used by goto, queues and patrols. */
public class BotScript {

    private final FakePlayersPlugin plugin;
    private final File file;
    private final Map<String, Location> waypoints = new LinkedHashMap<>();

    public BotScript(FakePlayersPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "waypoints.yml");
    }

    public void load() {
        waypoints.clear();
        if (!file.exists()) {
            return;
        }
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        for (String k : y.getKeys(false)) {
            Location l = y.getLocation(k);
            if (l != null) {
                waypoints.put(k.toLowerCase(Locale.ROOT), l);
            }
        }
    }

    private void save() {
        YamlConfiguration y = new YamlConfiguration();
        for (Map.Entry<String, Location> e : waypoints.entrySet()) {
            y.set(e.getKey(), e.getValue());
        }
        try {
            y.save(file);
        } catch (IOException ex) {
            plugin.getLogger().warning("Could not save waypoints.yml: " + ex.getMessage());
        }
    }

    public void set(String name, Location l) {
        waypoints.put(name.toLowerCase(Locale.ROOT), l.clone());
        save();
    }

    public boolean remove(String name) {
        boolean had = waypoints.remove(name.toLowerCase(Locale.ROOT)) != null;
        if (had) {
            save();
        }
        return had;
    }

    public Location get(String name) {
        Location l = waypoints.get(name.toLowerCase(Locale.ROOT));
        return l == null ? null : l.clone();
    }

    public List<String> names() {
        return new ArrayList<>(waypoints.keySet());
    }
}
