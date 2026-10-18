package dev.keelfy.provenance.core;

/**
 * A {@link LedgerEvent} in the journal: gapless increasing {@code seq}, wall-clock {@code time} in millis, and the
 * cause.
 *
 * @author keelfy
 */
public record Envelope(long seq, long time, Cause cause, LedgerEvent event) {}
