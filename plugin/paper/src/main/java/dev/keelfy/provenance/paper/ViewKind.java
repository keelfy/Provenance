package dev.keelfy.provenance.paper;

/**
 * How an open inventory view is settled against the ledger.
 *
 * @author keelfy
 */
public enum ViewKind {
    /** Top inventory is a real holder; opposite count changes are transfers. */
    CONTAINER,
    /** Crafting grid or workstation; its input slots count as the player's, changes are conversions. */
    WORKSTATION,
    /** Villager trading; payment slots count as the player's, new items are minted as trade. */
    MERCHANT,
    /** Only the player is involved: own inventory, ender chest, creative menu, plugin GUIs. */
    SELF;

    public boolean countsTopAsPlayer() {
        return this == WORKSTATION || this == MERCHANT;
    }
}
