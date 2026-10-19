package dev.keelfy.provenance.paper;

import dev.keelfy.provenance.app.port.OnlinePlayer;
import dev.keelfy.provenance.app.port.PlayerDirectory;
import java.util.Collection;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.val;
import org.bukkit.Server;
import org.jspecify.annotations.Nullable;

/** @author keelfy */
@RequiredArgsConstructor
public final class PaperDirectory implements PlayerDirectory {

    private final Server server;

    @Override
    public @Nullable String nameOf(UUID id) {
        return server.getOfflinePlayer(id).getName();
    }

    @Override
    public @Nullable UUID idOf(String name) {
        val player = server.getOfflinePlayerIfCached(name);
        return player != null ? player.getUniqueId() : null;
    }

    @Override
    public Collection<? extends OnlinePlayer> online() {
        return server.getOnlinePlayers().stream().map(PaperPlayer::of).toList();
    }
}
