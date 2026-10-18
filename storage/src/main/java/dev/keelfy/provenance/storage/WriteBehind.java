package dev.keelfy.provenance.storage;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import org.jetbrains.annotations.NotNull;

import dev.keelfy.provenance.core.LedgerChanges;
import lombok.val;

/**
 * Writes {@link LedgerChanges} batches on a single background thread, in submission order.
 * A failed batch is retried ahead of the next one, so nothing is dropped while the database recovers.
 *
 * @author keelfy
 */
public final class WriteBehind implements AutoCloseable {

    private static final long CLOSE_TIMEOUT_SECONDS = 30;

    private final @NotNull ChangeSink store;
    private final @NotNull Consumer<Exception> onFailure;
    private final @NotNull ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
        val thread = new Thread(runnable, "provenance-writer");
        thread.setDaemon(true);
        return thread;
    });
    /** Touched only by the writer thread. */
    private final @NotNull Deque<LedgerChanges> failed = new ArrayDeque<>();

    public WriteBehind(@NotNull ChangeSink store, @NotNull Consumer<Exception> onFailure) {
        this.store = store;
        this.onFailure = onFailure;
    }

    public void submit(@NotNull LedgerChanges changes) {
        if (!changes.isEmpty()) {
            executor.execute(() -> writeAll(changes));
        }
    }

    private void writeAll(LedgerChanges changes) {
        failed.addLast(changes);
        try {
            while (!failed.isEmpty()) {
                store.write(failed.peekFirst());
                failed.removeFirst();
            }
        } catch (Exception e) {
            onFailure.accept(e);
        }
    }

    /** Waits for queued batches, then closes the store. */
    @Override
    public void close() throws Exception {
        executor.shutdown();
        try {
            executor.awaitTermination(CLOSE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } finally {
            store.close();
        }
    }
}
