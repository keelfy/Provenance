package dev.keelfy.provenance.app;

import static org.assertj.core.api.Assertions.assertThat;

import dev.keelfy.provenance.app.port.Log;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class DrainableSchedulerTest {

    private final Fakes.ManualScheduler platform = new Fakes.ManualScheduler();
    private final List<String> messages = new ArrayList<>();
    private final DrainableScheduler scheduler = new DrainableScheduler(platform, new Log() {
        @Override
        public void info(String message) {}

        @Override
        public void warning(String message, Throwable cause) {}

        @Override
        public void severe(String message) {
            messages.add(message);
        }
    });
    private final List<String> ran = new ArrayList<>();

    @Test
    void drainRunsQueuedTasksInOrderIncludingOnesTheyQueue() {
        scheduler.runNextTick(() -> {
            ran.add("first");
            scheduler.runNextTick(() -> ran.add("nested"));
        });
        scheduler.runNextTick(() -> ran.add("second"));

        scheduler.drain();

        assertThat(ran).containsExactly("first", "second", "nested");
    }

    @Test
    void drainedTasksDoNotRunAgainWhenThePlatformGetsToThem() {
        scheduler.runNextTick(() -> ran.add("once"));

        scheduler.drain();
        platform.tick();

        assertThat(ran).containsExactly("once");
    }

    @Test
    void tasksNotDrainedStillRunOnThePlatformTick() {
        scheduler.runNextTick(() -> ran.add("tick"));

        platform.tick();
        scheduler.drain();

        assertThat(ran).containsExactly("tick");
    }

    @Test
    void failingTaskIsLoggedAndDoesNotStopTheRest() {
        scheduler.runNextTick(() -> {
            throw new IllegalStateException("boom");
        });
        scheduler.runNextTick(() -> ran.add("after"));

        scheduler.drain();

        assertThat(ran).containsExactly("after");
        assertThat(messages).singleElement().asString().contains("boom");
    }
}
