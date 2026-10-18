package dev.keelfy.provenance;

import java.util.logging.Logger;

import org.bukkit.Server;
import org.bukkit.plugin.java.JavaPlugin;
import org.jspecify.annotations.NonNull;

import com.google.inject.AbstractModule;

import dev.keelfy.provenance.core.Ledger;
import dev.keelfy.provenance.paper.inventory.InventoryReconciler;
import dev.keelfy.provenance.paper.world.SpawnContexts;
import lombok.RequiredArgsConstructor;
import lombok.val;

/**
 * @author keelfy
 */
@RequiredArgsConstructor
public final class ProvenanceModule extends AbstractModule {

    private final @NonNull ProvenancePlugin plugin;

    @Override
    protected void configure() {
        val ledger = new Ledger();
        val contexts = new SpawnContexts(plugin);
        val reconciler = new InventoryReconciler(plugin, ledger);
        val storage = new ProvenanceStorage(plugin, plugin.getLogger(), plugin.getServer(), ledger);

        bind(ProvenancePlugin.class).toInstance(plugin);
        bind(JavaPlugin.class).toInstance(plugin);
        bind(Server.class).toInstance(plugin.getServer());
        bind(Logger.class).toInstance(plugin.getLogger());
        bind(Ledger.class).toInstance(ledger);
        bind(SpawnContexts.class).toInstance(contexts);
        bind(InventoryReconciler.class).toInstance(reconciler);
        bind(ProvenanceStorage.class).toInstance(storage);
    }
}
