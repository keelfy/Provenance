package dev.keelfy.provenance.storage;

/**
 * Where the data lives. For SQLite {@code name} is the file in the plugin's data directory and the rest is unused.
 *
 * @author keelfy
 */
public record Database(Dialect dialect, String host, int port, String name, String user, String password) {

    public static Database sqlite(String file) {
        return new Database(Dialect.SQLITE, "", 0, file, "", "");
    }
}
