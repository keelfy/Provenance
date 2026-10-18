package dev.keelfy.provenance.storage;

import dev.keelfy.provenance.core.Envelope;
import dev.keelfy.provenance.core.HolderId;
import dev.keelfy.provenance.core.ItemKey;
import dev.keelfy.provenance.core.LedgerChanges;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import lombok.val;
import org.jspecify.annotations.Nullable;

/**
 * Writes {@link LedgerChanges} batches on a single background thread, in submission order. A failed batch is retried
 * ahead of the next one, so nothing is dropped while the database recovers.
 *
 * @author keelfy
 */
public final class WriteBehind implements AutoCloseable {

    private final ChangeSink store;
    private final Consumer<Exception> onFailure;
    private final Duration closeTimeout;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
        val thread = new Thread(runnable, "provenance-writer");
        thread.setDaemon(true);
        return thread;
    });
    /** Touched only by the writer thread. */
    private final Deque<LedgerChanges> failed = new ArrayDeque<>();

    /** @param closeTimeout how long {@link #close} waits for queued batches to be written */
    public WriteBehind(ChangeSink store, Consumer<Exception> onFailure, Duration closeTimeout) {
        this.store = store;
        this.onFailure = onFailure;
        this.closeTimeout = closeTimeout;
    }

    public void submit(LedgerChanges changes) {
        if (!changes.isEmpty()) {
            executor.execute(() -> writeAll(changes));
        }
    }

    /** Runs on the writer thread, so it sees every batch submitted before it. */
    public CompletableFuture<List<Envelope>> history(HolderId holder, @Nullable ItemKey item, int limit) {
        val result = new CompletableFuture<List<Envelope>>();
        executor.execute(() -> {
            try {
                result.complete(store.history(holder, item, limit));
            } catch (Exception e) {
                result.completeExceptionally(e);
            }
        });
        return result;
    }

    /** Same threading as {@link #history}. */
    public CompletableFuture<Trace> trace(HolderId root, @Nullable ItemKey item, long since, int depth, int limit) {
        val result = new CompletableFuture<Trace>();
        executor.execute(() -> {
            try {
                result.complete(store.trace(root, item, since, depth, limit));
            } catch (Exception e) {
                result.completeExceptionally(e);
            }
        });
        return result;
    }

    public void prune(long now) {
        executor.execute(() -> {
            try {
                store.prune(now);
            } catch (Exception e) {
                onFailure.accept(e);
            }
        });
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
            executor.awaitTermination(closeTimeout.toMillis(), TimeUnit.MILLISECONDS);
        } finally {
            store.close();
        }
    }
}
