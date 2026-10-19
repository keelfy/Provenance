package dev.keelfy.provenance.paper;

import dev.keelfy.provenance.app.port.Log;
import java.util.logging.Level;
import java.util.logging.Logger;
import lombok.RequiredArgsConstructor;

/** @author keelfy */
@RequiredArgsConstructor
public final class PaperLog implements Log {

    private final Logger logger;

    @Override
    public void info(String message) {
        logger.info(message);
    }

    @Override
    public void warning(String message, Throwable cause) {
        logger.log(Level.WARNING, message, cause);
    }

    @Override
    public void severe(String message) {
        logger.severe(message);
    }
}
