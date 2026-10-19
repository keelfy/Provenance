package dev.keelfy.provenance;

import com.google.inject.Guice;
import com.google.inject.Injector;
import dev.keelfy.provenance.app.ProvenanceStorage;
import dev.keelfy.provenance.app.inventory.InventoryReconciler;
import dev.keelfy.provenance.paper.command.ProvCommand;
import dev.keelfy.provenance.paper.listener.AutomationListener;
import dev.keelfy.provenance.paper.listener.InventoryListener;
import dev.keelfy.provenance.paper.listener.ItemEntityListener;
import dev.keelfy.provenance.paper.listener.ShowcaseListener;
import dev.keelfy.provenance.paper.listener.WorldListener;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import java.util.Objects;
import lombok.val;
import org.bukkit.plugin.java.JavaPlugin;

/** @author keelfy */
public final class ProvenancePlugin extends JavaPlugin {

    @SuppressWarnings("null")
    private Injector injector;

    @Override
    public void onEnable() {
        // set up dependency injection
        try {
            injector = Guice.createInjector(new ProvenanceModule(this));
        } catch (Exception e) {
            getLogger().severe("Failed to set up dependency injection: " + e.getMessage());
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        // set up the storage
        val storage = Objects.requireNonNull(injector.getInstance(ProvenanceStorage.class));
        if (!storage.open()) {
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        registerListeners();

        val reconciler = Objects.requireNonNull(injector.getInstance(InventoryReconciler.class));

        // set scheduled tasks
        reconciler.runScheduler();
        storage.runScheduler();

        // register commands
        getLifecycleManager()
                .registerEventHandler(LifecycleEvents.COMMANDS, event -> registerCommands(event.registrar()));

        getLogger().info("Provenance ledger is ready");
    }

    private void registerListeners() {
        val plugins = getServer().getPluginManager();
        plugins.registerEvents(injector.getInstance(InventoryListener.class), this);
        plugins.registerEvents(injector.getInstance(ItemEntityListener.class), this);
        plugins.registerEvents(injector.getInstance(WorldListener.class), this);
        plugins.registerEvents(injector.getInstance(AutomationListener.class), this);
        plugins.registerEvents(injector.getInstance(ShowcaseListener.class), this);
    }

    private void registerCommands(Commands registrar) {
        val command = Objects.requireNonNull(injector.getInstance(ProvCommand.class));
        val commandNode = Objects.requireNonNull(command.build());
        registrar.register(commandNode, "Item provenance and player correlations");
    }

    @Override
    public void onDisable() {
        val storage = Objects.requireNonNull(injector.getInstance(ProvenanceStorage.class));
        storage.close();
    }
}
