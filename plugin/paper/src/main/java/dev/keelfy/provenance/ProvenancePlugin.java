package dev.keelfy.provenance;

import com.google.inject.Guice;
import com.google.inject.Injector;
import dev.keelfy.provenance.app.DrainableScheduler;
import dev.keelfy.provenance.app.Integrations;
import dev.keelfy.provenance.app.ProvenanceStorage;
import dev.keelfy.provenance.app.command.ProvService;
import dev.keelfy.provenance.app.inventory.InventoryReconciler;
import dev.keelfy.provenance.paper.PaperDirectory;
import dev.keelfy.provenance.paper.command.ProvCommand;
import dev.keelfy.provenance.paper.husksync.HuskSyncBridge;
import dev.keelfy.provenance.paper.listener.AutomationListener;
import dev.keelfy.provenance.paper.listener.InventoryListener;
import dev.keelfy.provenance.paper.listener.ItemEntityListener;
import dev.keelfy.provenance.paper.listener.ShowcaseListener;
import dev.keelfy.provenance.paper.listener.WorldListener;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import java.util.Objects;
import lombok.val;
import org.bukkit.event.HandlerList;
import org.bukkit.plugin.java.JavaPlugin;
import org.jspecify.annotations.Nullable;

/** @author keelfy */
public final class ProvenancePlugin extends JavaPlugin {

    private static final String HUSKSYNC = "HuskSync";

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

        if (!start()) {
            return;
        }

        // register commands
        getLifecycleManager()
                .registerEventHandler(LifecycleEvents.COMMANDS, event -> registerCommands(event.registrar()));

        getLogger().info("Provenance ledger is ready");
    }

    /**
     * Opens the storage, then wires listeners and scheduled tasks; disables the plugin if the storage is unavailable.
     */
    private boolean start() {
        val storage = Objects.requireNonNull(injector.getInstance(ProvenanceStorage.class));
        if (!storage.open()) {
            getServer().getPluginManager().disablePlugin(this);
            return false;
        }

        registerListeners();

        val reconciler = Objects.requireNonNull(injector.getInstance(InventoryReconciler.class));

        // set scheduled tasks
        reconciler.runScheduler();
        storage.runScheduler();
        return true;
    }

    /** Finishes the queued next-tick work first: cancelling the tasks below would drop it. */
    private void stop() {
        Objects.requireNonNull(injector.getInstance(DrainableScheduler.class)).drain();
        HandlerList.unregisterAll(this);
        getServer().getScheduler().cancelTasks(this);
        Objects.requireNonNull(injector.getInstance(ProvenanceStorage.class)).close();
    }

    /**
     * Re-reads {@code config.yml} and rebuilds the ledger from the storage. A config that does not parse is rejected
     * before anything is stopped.
     *
     * @return why the reload failed, {@code null} if it did not
     */
    private @Nullable String reload() {
        reloadConfig();

        Injector reloaded;
        try {
            reloaded = Guice.createInjector(new ProvenanceModule(this));
        } catch (Exception e) {
            getLogger().severe("Reload rejected: " + e.getMessage());
            return "Config is invalid, still running on the old one (see console)";
        }

        stop();
        injector = reloaded;
        if (!start()) {
            return "Storage is unavailable, plugin disabled (see console)";
        }

        getLogger().info("Provenance reloaded");
        return null;
    }

    /** HuskSync is optional: nothing here touches its classes unless it is running and switched on. */
    public boolean usesHuskSync(Integrations integrations) {
        return integrations.huskSync() && getServer().getPluginManager().isPluginEnabled(HUSKSYNC);
    }

    private boolean usesHuskSync() {
        return usesHuskSync(injector.getInstance(Integrations.class));
    }

    private void registerListeners() {
        val plugins = getServer().getPluginManager();
        plugins.registerEvents(injector.getInstance(InventoryListener.class), this);
        plugins.registerEvents(injector.getInstance(ItemEntityListener.class), this);
        plugins.registerEvents(injector.getInstance(WorldListener.class), this);
        plugins.registerEvents(injector.getInstance(AutomationListener.class), this);
        plugins.registerEvents(injector.getInstance(ShowcaseListener.class), this);

        if (usesHuskSync()) {
            val bridge = injector.getInstance(HuskSyncBridge.class);
            bridge.register();
            plugins.registerEvents(bridge, this);
        }
    }

    private void registerCommands(Commands registrar) {
        // the command outlives a reload, so it looks the service up each time
        val command = new ProvCommand(
                () -> Objects.requireNonNull(injector.getInstance(ProvService.class)),
                new PaperDirectory(getServer()),
                this::reload);
        registrar.register(Objects.requireNonNull(command.build()), "Item provenance and player correlations");
    }

    @Override
    public void onDisable() {
        if (injector != null && usesHuskSync()) {
            injector.getInstance(HuskSyncBridge.class).persistAll();
        }
        stop();
    }
}
