package dev.keelfy.provenance.paper;

import dev.keelfy.provenance.app.port.ConfigSource;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import lombok.RequiredArgsConstructor;
import lombok.val;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

/** @author keelfy */
@RequiredArgsConstructor
public final class PaperConfig implements ConfigSource {

    private final ConfigurationSection section;

    /**
     * Reads a config file of the plugin's folder, writing the bundled one there first if it is missing. Keys the
     * server's file lacks fall back to the bundled one.
     */
    public static PaperConfig load(JavaPlugin plugin, String name) {
        val path = plugin.getDataFolder().toPath().resolve(name);
        if (!Files.exists(path)) {
            plugin.saveResource(name, false);
        }

        val config = YamlConfiguration.loadConfiguration(path.toFile());
        try (val bundled = new InputStreamReader(plugin.getResource(name), StandardCharsets.UTF_8)) {
            config.setDefaults(YamlConfiguration.loadConfiguration(bundled));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return new PaperConfig(config);
    }

    @Override
    public boolean getBoolean(String key) {
        return section.getBoolean(key);
    }

    @Override
    public long getLong(String key) {
        return section.getLong(key);
    }

    @Override
    public double getDouble(String key) {
        return section.getDouble(key);
    }

    @Override
    public String getString(String key, String fallback) {
        return section.getString(key, fallback);
    }
}
