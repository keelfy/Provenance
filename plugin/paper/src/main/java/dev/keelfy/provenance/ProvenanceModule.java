package dev.keelfy.provenance;

import com.google.inject.AbstractModule;
import dev.keelfy.provenance.app.ProvenanceStorage;
import dev.keelfy.provenance.app.Settings;
import dev.keelfy.provenance.app.command.ProvService;
import dev.keelfy.provenance.app.inventory.InventoryReconciler;
import dev.keelfy.provenance.app.observe.AutomationEvents;
import dev.keelfy.provenance.app.observe.ItemEvents;
import dev.keelfy.provenance.app.observe.ShowcaseEvents;
import dev.keelfy.provenance.app.observe.WorldEvents;
import dev.keelfy.provenance.app.port.PlayerDirectory;
import dev.keelfy.provenance.app.web.ReportPublisher;
import dev.keelfy.provenance.app.world.SpawnContexts;
import dev.keelfy.provenance.core.Ledger;
import dev.keelfy.provenance.paper.PaperConfig;
import dev.keelfy.provenance.paper.PaperDirectory;
import dev.keelfy.provenance.paper.PaperLog;
import dev.keelfy.provenance.paper.PaperScheduler;
import lombok.RequiredArgsConstructor;
import lombok.val;

/**
 * Builds the application layer on top of the Paper adapters and hands it to the listeners and the command.
 *
 * @author keelfy
 */
@RequiredArgsConstructor
public final class ProvenanceModule extends AbstractModule {

    private final ProvenancePlugin plugin;

    @Override
    protected void configure() {
        plugin.saveDefaultConfig();
        val settings = Settings.from(new PaperConfig(plugin.getConfig()));
        val scheduler = new PaperScheduler(plugin);
        val log = new PaperLog(plugin.getLogger());
        val directory = new PaperDirectory(plugin.getServer());
        val ledger = new Ledger(System::currentTimeMillis, settings.mergeAutomation());
        val contexts = new SpawnContexts(scheduler);
        val reconciler = new InventoryReconciler(scheduler, directory, ledger, settings);
        val storage = new ProvenanceStorage(plugin.getDataFolder().toPath(), log, scheduler, ledger, settings);
        val publisher = new ReportPublisher(settings);

        bind(ProvenancePlugin.class).toInstance(plugin);
        bind(ProvenanceStorage.class).toInstance(storage);
        bind(InventoryReconciler.class).toInstance(reconciler);
        bind(PlayerDirectory.class).toInstance(directory);
        bind(WorldEvents.class).toInstance(new WorldEvents(settings, ledger, contexts, reconciler, scheduler));
        bind(AutomationEvents.class).toInstance(new AutomationEvents(settings, ledger, contexts));
        bind(ItemEvents.class).toInstance(new ItemEvents(ledger, reconciler, scheduler));
        bind(ShowcaseEvents.class).toInstance(new ShowcaseEvents(settings, contexts, reconciler));
        bind(ProvService.class)
                .toInstance(
                        new ProvService(ledger, reconciler, storage, publisher, directory, scheduler, log, settings));
    }
}
