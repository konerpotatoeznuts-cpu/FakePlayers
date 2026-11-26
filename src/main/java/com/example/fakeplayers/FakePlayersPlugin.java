package com.example.fakeplayers;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

public class FakePlayersPlugin extends JavaPlugin {

    private BotManager manager;
    private final BotLinks links = new BotLinks();
    private BotScript scripts;
    private final MiniMessage mm = MiniMessage.miniMessage();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        if (!new java.io.File(getDataFolder(), "kits.yml").exists()) {
            saveResource("kits.yml", false);
        }
        PvpOptions.writeDefaults(getConfig());
        saveConfig();
        scripts = new BotScript(this);
        scripts.load();
        manager = new BotManager(this);
        manager.load();

        BotCommand cmd = new BotCommand(this);
        PluginCommand pc = getCommand("bot");
        if (pc != null) {
            pc.setExecutor(cmd);
            pc.setTabCompleter(cmd);
        }
        getServer().getPluginManager().registerEvents(new BotListener(this), this);
        getServer().getScheduler().runTaskTimer(this, manager::tick, 1L, 1L);
        getServer().getScheduler().runTaskTimer(this, manager::save, 6000L, 6000L);
    }

    @Override
    public void onDisable() {
        if (manager != null) {
            for (Bot b : manager.all()) {
                manager.tab().hideAll(b);
            }
            manager.cleanupPlaced();
            manager.save();
        }
    }

    /** Names of the presets defined in config.yml. */
    /** Kits from kits.yml (wins) merged with the older pvp.kits in config.yml. */
    public java.util.List<String> kitLines(String name) {
        org.bukkit.configuration.file.YamlConfiguration y = org.bukkit.configuration.file.YamlConfiguration
                .loadConfiguration(new java.io.File(getDataFolder(), "kits.yml"));
        java.util.List<String> l = y.getStringList("kits." + name);
        return l.isEmpty() ? getConfig().getStringList("pvp.kits." + name) : l;
    }

    public java.util.List<String> kitNames() {
        java.util.Set<String> out = new java.util.LinkedHashSet<>();
        org.bukkit.configuration.file.YamlConfiguration y = org.bukkit.configuration.file.YamlConfiguration
                .loadConfiguration(new java.io.File(getDataFolder(), "kits.yml"));
        org.bukkit.configuration.ConfigurationSection a = y.getConfigurationSection("kits");
        if (a != null) {
            out.addAll(a.getKeys(false));
        }
        org.bukkit.configuration.ConfigurationSection c = getConfig().getConfigurationSection("pvp.kits");
        if (c != null) {
            out.addAll(c.getKeys(false));
        }
        return new java.util.ArrayList<>(out);
    }

    public java.util.List<String> presets() {
        org.bukkit.configuration.ConfigurationSection s = getConfig().getConfigurationSection("pvp.presets");
        return s == null ? java.util.List.of() : new java.util.ArrayList<>(s.getKeys(false));
    }

    /** Sends a MiniMessage line with the plugin prefix. */
    public void info(CommandSender to, String miniMessage) {
        to.sendMessage(mm.deserialize(getConfig().getString("messages.prefix", "") + miniMessage));
    }

    public BotScript scripts() {
        return scripts;
    }

    public BotLinks links() {
        return links;
    }

    public BotManager manager() {
        return manager;
    }

    public Component parse(String s, TagResolver... resolvers) {
        return mm.deserialize(s, resolvers);
    }

    public void msg(CommandSender to, String key, String... kv) {
        String s = getConfig().getString("messages." + key, key);
        String prefix = getConfig().getString("messages.prefix", "");
        for (int i = 0; i + 1 < kv.length; i += 2) {
            s = s.replace("%" + kv[i] + "%", kv[i + 1]);
        }
        to.sendMessage(mm.deserialize(prefix + s));
    }
}
