package dev.keelfy.provenance.core;

import java.util.Arrays;
import java.util.stream.Collectors;
import lombok.val;
import org.jspecify.annotations.Nullable;

/**
 * What a block or an entity was when an event happened: its {@code type} (the namespaced key, {@code minecraft:chest},
 * {@code mymod:ruby_golem}) and the custom name it had, if any.
 *
 * @author keelfy
 */
public record HolderInfo(String type, @Nullable String label) {

    /**
     * {@code minecraft:netherite_ingot} -> {@code Netherite Ingot}; other namespaces stay visible: {@code Ruby Gem
     * (mymod)}.
     */
    public static String title(String key) {
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

    public String typeName() {
        return title(type);
    }

    /** The custom name if there is one, otherwise the type. */
    public String displayName() {
        return label != null ? label : typeName();
    }
}
