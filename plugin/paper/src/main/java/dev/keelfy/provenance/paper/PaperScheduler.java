package dev.keelfy.provenance.paper;

import dev.keelfy.provenance.app.port.Scheduler;
import lombok.RequiredArgsConstructor;
import org.bukkit.plugin.Plugin;

/** @author keelfy */
@RequiredArgsConstructor
public final class PaperScheduler implements Scheduler {

    private final Plugin plugin;

    @Override
    public void runNextTick(Runnable task) {
        plugin.getServer().getScheduler().runTask(plugin, task);
    }

    @Override
    public void runEvery(long delayTicks, long periodTicks, Runnable task) {
        plugin.getServer().getScheduler().runTaskTimer(plugin, task, delayTicks, periodTicks);
    }
}
