package dbviewer;

import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Token passed to a running query so the UI can stop it. Cancelling sets a flag checked between
 * statements and rows, and also calls {@link Statement#cancel()} on the current statement so a
 * blocked database call can be interrupted.
 */
final class QueryCancel {

    private final AtomicBoolean cancelled = new AtomicBoolean();
    private volatile Statement statement;

    boolean isCancelled() {
        return cancelled.get();
    }

    /** Registers the statement currently being executed with this token. */
    void watch(Statement statement) {
        this.statement = statement;
        if (cancelled.get()) {
            cancelStatement();
        }
    }

    /** Unregisters a statement once it has finished. */
    void unwatch(Statement statement) {
        if (this.statement == statement) {
            this.statement = null;
        }
    }

    void cancel() {
        cancelled.set(true);
        cancelStatement();
    }

    private void cancelStatement() {
        Statement current = statement;
        if (current != null) {
            try {
                current.cancel();
            } catch (SQLException ignored) {
                // The statement may already have finished; the flag still stops the script.
            }
        }
    }
}
