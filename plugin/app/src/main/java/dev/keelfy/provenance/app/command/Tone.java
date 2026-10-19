package dev.keelfy.provenance.app.command;

/**
 * How a piece of output looks, independent of the platform's text and colors.
 *
 * @author keelfy
 */
public enum Tone {
    /** Ordinary reply. */
    PLAIN,
    /** Background detail, e.g. the age and cause of a journal entry. */
    DIM,
    /** Errors and suspicious entries. */
    ALERT,
    /** Something to click. */
    ACCENT
}
