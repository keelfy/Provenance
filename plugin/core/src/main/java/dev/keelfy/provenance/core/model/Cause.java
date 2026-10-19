package dev.keelfy.provenance.core.model;

import java.util.Objects;

/**
 * Why a ledger event happened, as seen by the caller: a short stable key such as {@code mine} or {@code hopper}. Opaque
 * to the core; platforms pick from the constants below or add their own.
 *
 * @author keelfy
 */
public record Cause(String key) {

    public static final Cause MINE = new Cause("mine");
    public static final Cause KILL = new Cause("kill");
    public static final Cause LOOT = new Cause("loot");
    public static final Cause PICKUP = new Cause("pickup");
    public static final Cause DROP = new Cause("drop");
    public static final Cause CONTAINER = new Cause("container");
    public static final Cause CRAFT = new Cause("craft");
    public static final Cause HOPPER = new Cause("hopper");
    public static final Cause SMELT = new Cause("smelt");
    public static final Cause TRADE = new Cause("trade");
    public static final Cause RECONCILE = new Cause("reconcile");

    public Cause {
        Objects.requireNonNull(key, "key");
    }
}
