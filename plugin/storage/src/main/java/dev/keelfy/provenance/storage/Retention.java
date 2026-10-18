package dev.keelfy.provenance.storage;

import java.time.Duration;

/**
 * How long journal entries are kept.
 *
 * @param durable events that involve a player or create items
 * @param automation pure automation (block to block); it dominates volume and matters little for moderation
 * @author keelfy
 */
public record Retention(Duration durable, Duration automation) {}
