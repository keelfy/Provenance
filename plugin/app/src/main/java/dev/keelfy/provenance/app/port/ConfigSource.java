package dev.keelfy.provenance.app.port;

/**
 * Read access to the server's configuration file, whatever format the platform keeps it in. A missing key reads as the
 * platform's default (zero, {@code false}, the given fallback).
 *
 * @author keelfy
 */
public interface ConfigSource {

    boolean getBoolean(String key);

    long getLong(String key);

    double getDouble(String key);

    String getString(String key, String fallback);
}
