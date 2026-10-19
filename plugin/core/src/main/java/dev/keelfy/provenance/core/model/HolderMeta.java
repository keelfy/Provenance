package dev.keelfy.provenance.core.model;

import java.util.Arrays;
import java.util.stream.Collectors;
import lombok.val;
import org.jspecify.annotations.Nullable;

/**
 * What a block or an entity was when an event happened: its type, custom name and, for an entity, where it stood.
 * Travels with events to the journal but is not part of a holder's identity.
 *
 * @author keelfy
 */
public record HolderMeta(
        @Nullable String type,
        @Nullable String label,
        @Nullable Chunk chunk) {

    public static final HolderMeta NONE = new HolderMeta(null, null, null);

    /** The type as a name; holders of unknown type are called after their kind. */
    public String typeName(Kind kind) {
        return type != null ? title(type) : kind.name().toLowerCase();
    }

    /** The custom name if there is one, otherwise the type. */
    public String displayName(Kind kind) {
        return label != null ? label : typeName(kind);
    }

    /**
     * {@code minecraft:netherite_ingot} -> {@code Netherite Ingot}; other namespaces stay visible: {@code Ruby Gem
     * (mymod)}.
     */
    private static String title(String key) {
        val colon = key.indexOf(':');
        val name = Arrays.stream(key.substring(colon + 1).split("_"))
                .filter(word -> !word.isEmpty())
                .map(word -> Character.toUpperCase(word.charAt(0)) + word.substring(1))
                .collect(Collectors.joining(" "));
        if (colon < 0 || key.startsWith("minecraft:")) {
            return name;
        }
        return name + " (" + key.substring(0, colon) + ")";
    }
}
