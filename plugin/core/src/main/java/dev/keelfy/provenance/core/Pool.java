package dev.keelfy.provenance.core;

import lombok.Getter;

/**
 * Count of one item type inside one holder, with where it came from and who last deposited it.
 *
 * @author keelfy
 */
@Getter
public final class Pool {

    private long count;
    private TaintVector origin;
    private TaintVector custody;

    Pool(long count, TaintVector origin, TaintVector custody) {
        this.count = count;
        this.origin = origin;
        this.custody = custody;
    }

    void add(long amount, TaintVector addedOrigin, TaintVector addedCustody) {
        origin = TaintVector.mix(origin, count, addedOrigin, amount);
        custody = TaintVector.mix(custody, count, addedCustody, amount);
        count += amount;
    }

    /** Haircut removal: vectors stay unchanged. */
    void remove(long amount) {
        count -= amount;
    }
}
