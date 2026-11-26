package dbviewer;

import java.util.List;

/** The targets found for one foreign-key cell, and where they came from. */
record ForeignKeyLookup(List<ForeignKeyReference> options, boolean inferred, boolean remembered) {

    ForeignKeyLookup {
        options = List.copyOf(options);
    }
}
