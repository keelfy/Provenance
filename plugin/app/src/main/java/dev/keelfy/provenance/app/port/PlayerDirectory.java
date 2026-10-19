package dev.keelfy.provenance.app.port;

import java.util.Collection;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Who is, and who has been, on the server.
 *
 * @author keelfy
 */
public interface PlayerDirectory {

    /** Name of a player who has joined before, {@code null} if the server does not remember. */
    @Nullable String nameOf(UUID id);

    /** Id of a player the server knows by {@code name}, without asking Mojang. */
    @Nullable UUID idOf(String name);

    Collection<? extends OnlinePlayer> online();
}
