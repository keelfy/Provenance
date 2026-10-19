package dev.keelfy.provenance.app.command;

import java.util.List;

/**
 * One line of command output: the platform renders each span in its own text type.
 *
 * @author keelfy
 */
public record Line(List<Span> spans) {

    public static Line of(Span... spans) {
        return new Line(List.of(spans));
    }

    public static Line plain(String text) {
        return of(new Span(text, Tone.PLAIN));
    }

    public static Line alert(String text) {
        return of(new Span(text, Tone.ALERT));
    }
}
