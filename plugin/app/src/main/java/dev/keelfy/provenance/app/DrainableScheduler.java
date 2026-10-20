package dev.keelfy.provenance.app;

import dev.keelfy.provenance.app.port.Log;
import dev.keelfy.provenance.app.port.Scheduler;
import java.util.LinkedHashSet;
import java.util.Set;
import lombok.RequiredArgsConstructor;

/**
 * Lets the next-tick work be finished early: {@link #drain()} runs what is still queued, so it is not lost when the
 * platform cancels the plugin's tasks on a reload or shutdown. Like every {@link Scheduler}, it belongs to the main
 * thread.
 *
 * @author keelfy
 */
@RequiredArgsConstructor
public final class DrainableScheduler implements Scheduler {

    private final Scheduler delegate;
    private final Log log;
    private final Set<Runnable> pending = new LinkedHashSet<>();

    @Override
    public void runNextTick(Runnable task) {
        var once = new Once(task);
        pending.add(once);
        delegate.runNextTick(once);
    }

    @Override
    public void runEvery(long delayTicks, long periodTicks, Runnable task) {
        delegate.runEvery(delayTicks, periodTicks, task);
    }

    /** Runs the queued tasks in order, including the ones they queue; the platform's own turn for them is a no-op. */
    public void drain() {
        while (!pending.isEmpty()) {
            var next = pending.iterator().next();
            next.run();
        }
    }

    /** Runs {@code task} at most once, whichever of the platform and {@link #drain()} comes first. */
    @RequiredArgsConstructor
    private final class Once implements Runnable {

        private final Runnable task;

        @Override
        public void run() {
            if (!pending.remove(this)) {
                return;
            }

            try {
                task.run();
            } catch (RuntimeException e) {
                log.severe("Queued task failed: " + e);
            }
        }
    }
}
