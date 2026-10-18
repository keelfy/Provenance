package dev.keelfy.provenance.core;

import org.jetbrains.annotations.NotNull;

import lombok.Getter;

/** 
 * Count of one item type inside one holder, with where it came from and who last deposited it. 
 * 
 * @author keelfy
 * */
@Getter 
public final class Pool {

    private long count;
    private @NotNull TaintVector origin;
    private @NotNull TaintVector custody;

    Pool(long count, @NotNull TaintVector origin, @NotNull TaintVector custody) {
        this.count = count;
        this.origin = origin;
        this.custody = custody;
    }

    void add(long amount, @NotNull TaintVector addedOrigin, @NotNull TaintVector addedCustody) {
        origin = TaintVector.mix(origin, count, addedOrigin, amount);
        custody = TaintVector.mix(custody, count, addedCustody, amount);
        count += amount;
    }

    /** Haircut removal: vectors stay unchanged. */
    void remove(long amount) {
        count -= amount;
    }
}
