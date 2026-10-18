package dev.keelfy.provenance;

import com.google.inject.AbstractModule;
import dev.keelfy.provenance.core.Ledger;
import dev.keelfy.provenance.paper.inventory.InventoryReconciler;
import dev.keelfy.provenance.paper.world.SpawnContexts;
import lombok.RequiredArgsConstructor;
import lombok.val;
import org.bukkit.Server;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

/** @author keelfy */
@RequiredArgsConstructor
public final class ProvenanceModule extends AbstractModule {

    private final ProvenancePlugin plugin;

    @Override
    protected void configure() {
        plugin.saveDefaultConfig();
        val settings = Settings.from(plugin.getConfig());
        val ledger = new Ledger(System::currentTimeMillis, settings.mergeAutomation());
        val contexts = new SpawnContexts(plugin);
        val reconciler = new InventoryReconciler(plugin, ledger, settings);
        val storage = new ProvenanceStorage(plugin, plugin.getLogger(), plugin.getServer(), ledger, settings);

        bind(ProvenancePlugin.class).toInstance(plugin);
        bind(JavaPlugin.class).toInstance(plugin);
        bind(Plugin.class).toInstance(plugin);
        bind(Server.class).toInstance(plugin.getServer());
        bind(Settings.class).toInstance(settings);
        bind(Ledger.class).toInstance(ledger);
        bind(SpawnContexts.class).toInstance(contexts);
        bind(InventoryReconciler.class).toInstance(reconciler);
        bind(ProvenanceStorage.class).toInstance(storage);
    }
}
