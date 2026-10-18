package dev.keelfy.provenance.core;

import org.jetbrains.annotations.NotNull;

/** 
 * A link between two players, with flow and similarity metrics. 
 * 
 * @author keelfy
 * */
public record Link(@NotNull OriginId other, double sent, double received, double similarity) {
    public double volume() {
        return sent + received;
    }
}
