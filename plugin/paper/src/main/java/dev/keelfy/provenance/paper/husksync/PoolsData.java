package dev.keelfy.provenance.paper.husksync;

import dev.keelfy.provenance.core.event.PoolState;
import dev.keelfy.provenance.core.model.ItemKey;
import dev.keelfy.provenance.storage.VectorCodec;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.BiConsumer;
import lombok.val;
import net.william278.husksync.BukkitHuskSync;
import net.william278.husksync.adapter.Adaptable;
import net.william278.husksync.data.BukkitData;
import net.william278.husksync.user.BukkitUser;
import org.bukkit.entity.Player;
import org.jspecify.annotations.Nullable;

/**
 * What the ledger knew about a player's items when a server saved them: HuskSync stores it in the same snapshot as the
 * inventory, so the next server learns where the items came from. Plain fields for Gson, vectors in {@link VectorCodec}
 * form.
 *
 * @author keelfy
 */
public final class PoolsData extends BukkitData implements Adaptable {

    /** The server that saved it. */
    private String server;

    private Map<String, Pool> pools;

    /** Who is told when the data is applied; set by the serializer, never stored. */
    private transient @Nullable BiConsumer<Player, PoolsData> onApply;

    private static final class Pool {
        private long count;
        private String origin;
        private String custody;
    }

    @SuppressWarnings("unused") // Gson
    private PoolsData() {}

    public static PoolsData of(String server, Map<ItemKey, PoolState> pools) {
        val data = new PoolsData();
        data.server = server;
        data.pools = new TreeMap<>();
        pools.forEach((item, state) -> {
            val pool = new Pool();
            pool.count = state.count();
            pool.origin = VectorCodec.encode(state.origin());
            pool.custody = VectorCodec.encode(state.custody());
            data.pools.put(item.key(), pool);
        });
        return data;
    }

    public String server() {
        return server;
    }

    public Map<ItemKey, PoolState> pools() {
        val result = new TreeMap<ItemKey, PoolState>((a, b) -> a.key().compareTo(b.key()));
        pools.forEach((item, pool) -> result.put(
                new ItemKey(item),
                new PoolState(pool.count, VectorCodec.decode(pool.origin), VectorCodec.decode(pool.custody))));
        return result;
    }

    PoolsData whenApplied(BiConsumer<Player, PoolsData> onApply) {
        this.onApply = onApply;
        return this;
    }

    @Override
    public void apply(BukkitUser user, BukkitHuskSync plugin) {
        if (onApply != null) {
            onApply.accept(user.getPlayer(), this);
        }
    }
}
