package dev.keelfy.provenance.app.port;

/**
 * The platform's logger.
 *
 * @author keelfy
 */
public interface Log {

    void info(String message);

    void warning(String message, Throwable cause);

    void severe(String message);
}
