package dev.keelfy.provenance.core.event;

import dev.keelfy.provenance.core.taint.TaintVector;

/**
 * What one pool held at some moment: the count, where it came from and who held it. The value form of a pool, for
 * events that carry pools between ledgers.
 *
 * @author keelfy
 */
public record PoolState(long count, TaintVector origin, TaintVector custody) {}
