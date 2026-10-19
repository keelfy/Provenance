package dev.keelfy.provenance.storage;

import dev.keelfy.provenance.core.event.Envelope;
import java.util.List;

/**
 * Result of {@link ChangeSink#trace}: the events reachable from the root, oldest first, and whether the limit cut some
 * off.
 *
 * @author keelfy
 */
public record Trace(List<Envelope> events, boolean truncated) {}
