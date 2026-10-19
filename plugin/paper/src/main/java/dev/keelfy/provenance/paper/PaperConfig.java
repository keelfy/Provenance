package dev.keelfy.provenance.paper;

import dev.keelfy.provenance.app.port.ConfigSource;
import lombok.RequiredArgsConstructor;
import org.bukkit.configuration.ConfigurationSection;

/** @author keelfy */
@RequiredArgsConstructor
public final class PaperConfig implements ConfigSource {

    private final ConfigurationSection section;

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
