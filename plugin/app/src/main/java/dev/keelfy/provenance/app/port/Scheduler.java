package dev.keelfy.provenance.app.port;

/**
 * Runs work on the server's main thread. Time is in game ticks, which every platform shares.
 *
 * @author keelfy
 */
public interface Scheduler {

    /** Runs {@code task} on the next tick. */
    void runNextTick(Runnable task);

    /** Runs {@code task} after {@code delayTicks}, then every {@code periodTicks}. */
    void runEvery(long delayTicks, long periodTicks, Runnable task);
}
