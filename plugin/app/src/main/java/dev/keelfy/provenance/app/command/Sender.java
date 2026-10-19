package dev.keelfy.provenance.app.command;

import dev.keelfy.provenance.app.port.OnlinePlayer;
import org.jspecify.annotations.Nullable;

/**
 * Whoever ran a command: a player or the console.
 *
 * @author keelfy
 */
public interface Sender {

    void send(Line line);

    /** The player behind the sender, {@code null} for the console. */
    @Nullable OnlinePlayer player();

    default void reply(String text) {
        send(Line.plain(text));
    }
}
