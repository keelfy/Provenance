package dev.keelfy.provenance;

import dev.keelfy.provenance.core.Ledger;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

/**
 * @author keelfy
 */
public final class ProvenancePlugin extends JavaPlugin {

    private @NotNull Ledger ledger;

    @Override
    public void onEnable() {
        ledger = new Ledger();
        getLogger().info("Provenance ledger is ready");
    }

    public Ledger ledger() {
        return ledger;
    }
}
