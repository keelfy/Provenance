package dev.keelfy.provenance.paper;

import dev.keelfy.provenance.app.command.Line;
import dev.keelfy.provenance.app.command.Sender;
import dev.keelfy.provenance.app.command.Span;
import dev.keelfy.provenance.app.command.Tone;
import dev.keelfy.provenance.app.port.OnlinePlayer;
import lombok.val;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jspecify.annotations.Nullable;

/**
 * A Bukkit command sender; renders the application layer's lines as Adventure components.
 *
 * @author keelfy
 */
public record PaperSender(CommandSender sender) implements Sender {

    @Override
    public void send(Line line) {
        sender.sendMessage(render(line));
    }

    @Override
    public @Nullable OnlinePlayer player() {
        return sender instanceof Player player ? PaperPlayer.of(player) : null;
    }

    static Component render(Line line) {
        val spans = line.spans();
        Component result = render(spans.getFirst());
        for (val span : spans.subList(1, spans.size())) {
            result = result.append(render(span));
        }
        return result;
    }

    private static Component render(Span span) {
        val text = Component.text(span.text(), color(span.tone()));
        return span.url() != null ? text.clickEvent(ClickEvent.openUrl(span.url())) : text;
    }

    private static TextColor color(Tone tone) {
        return switch (tone) {
            case PLAIN -> NamedTextColor.GRAY;
            case DIM -> NamedTextColor.DARK_GRAY;
            case ALERT -> NamedTextColor.RED;
            case ACCENT -> NamedTextColor.AQUA;
        };
    }
}
