package dev.keelfy.provenance.core;

import static org.assertj.core.api.Assertions.assertThat;

import lombok.val;
import org.junit.jupiter.api.Test;

class HolderInfoTest {

    @Test
    void customNameBeatsTypeAndTypeIsTitleCased() {
        assertThat(new HolderInfo("minecraft:iron_golem", null).displayName()).isEqualTo("Iron Golem");
        assertThat(new HolderInfo("minecraft:zombie", "Steve").displayName()).isEqualTo("Steve");
        assertThat(new HolderInfo("minecraft:zombie", "Steve").typeName()).isEqualTo("Zombie");
    }

    @Test
    void namespaceOfModdedTypesStaysVisible() {
        assertThat(new HolderInfo("mymod:ruby_golem", null).displayName()).isEqualTo("Ruby Golem (mymod)");
    }

    @Test
    void identityIgnoresInfo() {
        val plain = HolderId.block("w", 1, 2, 3);
        val described = plain.described(new HolderInfo("minecraft:chest", null));
        assertThat(described).isEqualTo(plain).hasSameHashCodeAs(plain);
    }
}
