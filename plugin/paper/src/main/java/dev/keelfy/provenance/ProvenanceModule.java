package dev.keelfy.provenance;

import com.google.inject.AbstractModule;
import dev.keelfy.provenance.app.DrainableScheduler;
import dev.keelfy.provenance.app.Integrations;
import dev.keelfy.provenance.app.ProvenanceStorage;
import dev.keelfy.provenance.app.Settings;
import dev.keelfy.provenance.app.command.ProvService;
import dev.keelfy.provenance.app.inventory.InventoryReconciler;
import dev.keelfy.provenance.app.observe.AutomationEvents;
import dev.keelfy.provenance.app.observe.ItemEvents;
import dev.keelfy.provenance.app.observe.ShowcaseEvents;
import dev.keelfy.provenance.app.observe.WorldEvents;
import dev.keelfy.provenance.app.port.PlayerDirectory;
import dev.keelfy.provenance.app.port.PlayerSync;
import dev.keelfy.provenance.app.web.ReportPublisher;
import dev.keelfy.provenance.app.world.SpawnContexts;
import dev.keelfy.provenance.core.Ledger;
import dev.keelfy.provenance.paper.PaperConfig;
import dev.keelfy.provenance.paper.PaperDirectory;
import dev.keelfy.provenance.paper.PaperLog;
import dev.keelfy.provenance.paper.PaperScheduler;
import dev.keelfy.provenance.paper.husksync.HuskSyncBridge;
import dev.keelfy.provenance.paper.husksync.HuskSyncLock;
import lombok.RequiredArgsConstructor;
import lombok.val;

/**
 * Builds the application layer on top of the Paper adapters and hands it to the listeners and the command.
 *
 * @author keelfy
 */
@RequiredArgsConstructor
public final class ProvenanceModule extends AbstractModule {

    private static final String INTEGRATIONS = "integrations.yml";

    private final ProvenancePlugin plugin;

    @Override
    protected void configure() {
        plugin.saveDefaultConfig();
        val settings = Settings.from(new PaperConfig(plugin.getConfig()));
        val integrations = Integrations.from(PaperConfig.load(plugin, INTEGRATIONS));
        val log = new PaperLog(plugin.getLogger());
        val scheduler = new DrainableScheduler(new PaperScheduler(plugin), log);
        val directory = new PaperDirectory(plugin.getServer());
        val ledger = new Ledger(System::currentTimeMillis, settings.mergeAutomation());
        val contexts = new SpawnContexts(scheduler);
        val huskSync = plugin.usesHuskSync(integrations);
        val reconciler = new InventoryReconciler(
                scheduler, directory, ledger, settings, huskSync ? new HuskSyncLock() : PlayerSync.NONE);
        val storage = new ProvenanceStorage(plugin.getDataFolder().toPath(), log, scheduler, ledger, settings);
        val publisher = new ReportPublisher(settings);

        bind(Integrations.class).toInstance(integrations);
        bind(ProvenancePlugin.class).toInstance(plugin);
        bind(DrainableScheduler.class).toInstance(scheduler);
        bind(ProvenanceStorage.class).toInstance(storage);
        bind(InventoryReconciler.class).toInstance(reconciler);
        bind(PlayerDirectory.class).toInstance(directory);
        if (huskSync) {
            bind(HuskSyncBridge.class)
                    .toInstance(new HuskSyncBridge(plugin.getServer(), log, settings, ledger, reconciler, scheduler));
        }
        bind(WorldEvents.class).toInstance(new WorldEvents(settings, ledger, contexts, reconciler, scheduler));
        bind(AutomationEvents.class).toInstance(new AutomationEvents(settings, ledger, contexts));
        bind(ItemEvents.class).toInstance(new ItemEvents(ledger, reconciler, scheduler));
        bind(ShowcaseEvents.class).toInstance(new ShowcaseEvents(settings, contexts, reconciler));
        bind(ProvService.class)
                .toInstance(
                        new ProvService(ledger, reconciler, storage, publisher, directory, scheduler, log, settings));
    }
}
