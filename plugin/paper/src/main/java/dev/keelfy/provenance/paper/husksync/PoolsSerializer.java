package dev.keelfy.provenance.paper.husksync;

import java.util.function.BiConsumer;
import net.william278.husksync.HuskSync;
import net.william278.husksync.data.Serializer;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

/** @author keelfy */
final class PoolsSerializer extends Serializer.Json<PoolsData> {

    private final BiConsumer<Player, PoolsData> onApply;

    PoolsSerializer(HuskSync plugin, BiConsumer<Player, PoolsData> onApply) {
        super(plugin, PoolsData.class);
        this.onApply = onApply;
    }

    @Override
    public PoolsData deserialize(@NotNull String serialized) throws DeserializationException {
        return super.deserialize(serialized).whenApplied(onApply);
    }
}
