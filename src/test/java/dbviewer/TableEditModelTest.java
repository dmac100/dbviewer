package dbviewer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Types;
import java.util.List;

import org.junit.jupiter.api.Test;

class TableEditModelTest {

    @Test
    void tracksEditsAndCanRevertThem() {
        TableEditModel model = model();
        assertFalse(model.isDirty());

        model.setCell(0, 1, "Grace");
        assertTrue(model.isDirty());
        assertTrue(model.isCellEdited(0, 1));

        TableEditModel.Changes changes = model.changes("SQLite");
        assertEquals(1, changes.count());
        assertEquals(1, changes.updates().size());
        DatabaseService.RowUpdate update = changes.updates().get(0);
        assertEquals(List.of("id"), update.keyColumns());
        assertEquals(List.of(1), update.keyValues());
        assertEquals("Grace", update.values().get("name"));
        assertTrue(changes.summary().contains("Grace"));
        assertTrue(changes.summary().contains("\"people\""));

        model.setCell(0, 1, "Ada");
        assertFalse(model.isDirty());
        assertTrue(model.changes("SQLite").isEmpty());
    }

    @Test
    void addsRowsFromTheRowBelowTheResult() {
        TableEditModel model = model();
        assertEquals(2, model.rowCount());

        model.setCell(2, 0, "3");
        model.setCell(2, 1, "Cara");
        assertEquals(3, model.rowCount());
        assertEquals(1, model.newRowCount());
        assertTrue(model.isNewRow(2));
        assertTrue(model.isDirty());

        TableEditModel.Changes changes = model.changes("SQLite");
        assertEquals(1, changes.inserts().size());
        assertEquals(3, changes.inserts().get(0).values().get("id"));
        assertEquals("Cara", changes.inserts().get(0).values().get("name"));
        assertTrue(changes.summary().contains("New row 1"));

        model.setCell(2, 1, "");
        model.setCell(2, 0, "");
        assertEquals(2, model.rowCount());
        assertFalse(model.isDirty());
    }

    @Test
    void storesExplicitNullsAndRejectsInvalidNumbers() {
        TableEditModel model = model();
        model.setCell(0, 1, JdbcValues.NULL_TEXT);
        assertNull(model.changes("SQLite").updates().get(0).values().get("name"));

        model.setCell(0, 0, "not a number");
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> model.changes("SQLite"));
        assertTrue(error.getMessage().contains("integer"));
    }

    @Test
    void requiresPrimaryKeysInTheResult() {
        QueryResult.SourceTable source = source(List.of("id", "name"),
                List.of(Types.INTEGER, Types.VARCHAR), List.of("id"));
        QueryResult.SourceTable missingKey = source(List.of("id", "name"),
                List.of(Types.INTEGER, Types.VARCHAR), List.of("missing"));
        QueryResult.SourceTable noKeyInResult = new QueryResult.SourceTable("", "", "people",
                List.of("name"), List.of(Types.VARCHAR), List.of("id"));
        assertNull(TableEditModel.create(null, List.of("id"), List.of()));
        assertNull(TableEditModel.create(noKeyInResult, List.of("name"), List.of()));
        assertNull(TableEditModel.create(missingKey, List.of("id", "name"), List.of()));

        TableEditModel model = TableEditModel.create(source,
                List.of("id", "name"), List.<Object[]>of(new Object[] {1, "Ada"}));
        assertTrue(model.isColumnEditable(0));
        assertTrue(model.isColumnEditable(1));
    }

    @Test
    void anEmptyResultCanStillAddRows() {
        QueryResult.SourceTable source = source(List.of("id", "name"),
                List.of(Types.INTEGER, Types.VARCHAR), List.of("id"));
        TableEditModel model = TableEditModel.create(
                source, List.of("id", "name"), List.of());
        model.setCell(0, 1, "Ada");
        assertEquals(1, model.changes("SQLite").inserts().size());
    }

    @Test
    void committedRowsContainTheNewValues() {
        TableEditModel model = model();
        model.setCell(0, 1, "Grace");
        model.setCell(2, 0, "3");
        model.setCell(2, 1, "Cara");

        List<Object[]> saved = model.committedRows();
        assertEquals(3, saved.size());
        assertEquals("Grace", saved.get(0)[1]);
        assertEquals(3, saved.get(2)[0]);
        assertEquals("Cara", saved.get(2)[1]);
    }

    private TableEditModel model() {
        return TableEditModel.create(source(List.of("id", "name"),
                List.of(Types.INTEGER, Types.VARCHAR), List.of("id")),
                List.of("id", "name"),
                List.of(new Object[] {1, "Ada"}, new Object[] {2, "Bob"}));
    }

    private QueryResult.SourceTable source(List<String> names, List<Integer> types,
            List<String> keys) {
        return new QueryResult.SourceTable("", "", "people", names, types, keys);
    }
}
