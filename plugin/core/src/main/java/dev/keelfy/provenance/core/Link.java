package dev.keelfy.provenance.core;

/**
 * A link between two players, with flow and similarity metrics.
 *
 * @author keelfy
 */
public record Link(OriginId other, double sent, double received, double similarity) {
    public double volume() {
        return sent + received;
    }
}
