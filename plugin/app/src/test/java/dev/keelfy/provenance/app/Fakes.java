package dev.keelfy.provenance.app;

import dev.keelfy.provenance.app.port.ConfigSource;
import dev.keelfy.provenance.app.port.OnlinePlayer;
import dev.keelfy.provenance.app.port.PlayerDirectory;
import dev.keelfy.provenance.app.port.Scheduler;
import dev.keelfy.provenance.core.model.Holder;
import dev.keelfy.provenance.core.model.ItemKey;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** Stand-ins for the platform. */
public final class Fakes {

    private Fakes() {}

    /** Every number is 1, every text is empty: enough to build {@link Settings}. */
    public static Settings settings() {
        return Settings.from(new ConfigSource() {
            @Override
            public boolean getBoolean(String key) {
                return false;
            }

            @Override
            public long getLong(String key) {
                return 1;
            }

            @Override
            public double getDouble(String key) {
                return 1;
            }

            @Override
            public String getString(String key, String fallback) {
                return fallback;
            }
        });
    }

    /** A server where exactly these players are online and known by name. */
    public static PlayerDirectory directory(Player... online) {
        return new PlayerDirectory() {
            @Override
            public @Nullable String nameOf(UUID id) {
                return List.of(online).stream()
                        .filter(player -> player.id().equals(id))
                        .map(Player::name)
                        .findFirst()
                        .orElse(null);
            }

            @Override
            public @Nullable UUID idOf(String name) {
                return List.of(online).stream()
                        .filter(player -> player.name().equals(name))
                        .map(Player::id)
                        .findFirst()
                        .orElse(null);
            }

            @Override
            public Collection<? extends OnlinePlayer> online() {
                return List.of(online);
            }
        };
    }

    /** Runs what was queued when {@link #tick()} is called. */
    public static final class ManualScheduler implements Scheduler {

        private final List<Runnable> queued = new ArrayList<>();

        @Override
        public void runNextTick(Runnable task) {
            queued.add(task);
        }

        @Override
        public void runEvery(long delayTicks, long periodTicks, Runnable task) {}

        public void tick() {
            var now = List.copyOf(queued);
            queued.clear();
            now.forEach(Runnable::run);
        }
    }

    public static final class Player implements OnlinePlayer {

        private final UUID id = UUID.randomUUID();
        public final Map<ItemKey, Long> items = new HashMap<>();
        public boolean creative;
        public @Nullable Holder looking;

        @Override
        public UUID id() {
            return id;
        }

        @Override
        public String name() {
            return "Alice";
        }

        @Override
        public boolean creative() {
            return creative;
        }

        @Override
        public Map<ItemKey, Long> holdings() {
            return new HashMap<>(items);
        }

        @Override
        public @Nullable ItemKey mainHand() {
            return items.keySet().stream().findFirst().orElse(null);
        }

        @Override
        public @Nullable Holder lookingAt(int reach) {
            return looking;
        }
    }
}
