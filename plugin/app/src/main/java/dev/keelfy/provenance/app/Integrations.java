package dev.keelfy.provenance.app;

import dev.keelfy.provenance.app.port.ConfigSource;

/**
 * Which other plugins Provenance cooperates with, as set in {@code integrations.yml}. An integration only takes part if
 * the plugin is installed too.
 *
 * @author keelfy
 */
public record Integrations(boolean huskSync) {

    public static Integrations from(ConfigSource config) {
        return new Integrations(config.getBoolean("husksync.enabled"));
    }
}
