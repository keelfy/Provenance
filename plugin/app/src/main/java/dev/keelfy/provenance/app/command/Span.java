package dev.keelfy.provenance.app.command;

import org.jspecify.annotations.Nullable;

/**
 * A run of text in one tone; {@code url}, when set, is opened by clicking it.
 *
 * @author keelfy
 */
public record Span(String text, Tone tone, @Nullable String url) {

    public Span(String text, Tone tone) {
        this(text, tone, null);
    }
}
