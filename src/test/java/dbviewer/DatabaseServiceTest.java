package dbviewer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DatabaseServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void connectsListsTablesAndRunsQueries() throws Exception {
        Path db = tempDir.resolve("test.db");
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db);
                Statement s = c.createStatement()) {
            s.execute("CREATE TABLE people (id INTEGER PRIMARY KEY, name TEXT)");
            s.execute("INSERT INTO people (name) VALUES ('Ada')");
        }

        DatabaseService service = new DatabaseService();
        service.connect(profile(db));
        assertTrue(service.isConnected());

        List<TableInfo> tables = service.listTables();
        assertTrue(tables.stream().anyMatch(t -> t.name().equals("people")));

        QueryResult select = service.execute("SELECT id, name FROM people");
        assertEquals(List.of("id", "name"), select.columns());
        assertEquals(1, select.rows().size());
        assertEquals("Ada", select.rows().get(0)[1]);
        assertFalse(select.truncated());

        QueryResult update = service.execute("INSERT INTO people (name) VALUES ('Grace')");
        assertEquals(0, update.columns().size());
        assertEquals(1, update.totalUpdates());

        QueryResult script = service.execute(
                "SELECT name FROM people WHERE name='Grace'; "
                        + "UPDATE people SET name='Byron' WHERE name='Grace'; "
                        + "SELECT name FROM people WHERE name='Byron'");
        assertEquals(3, script.statementCount());
        assertEquals(1, script.totalUpdates());
        assertEquals(List.of("name"), script.columns());
        assertEquals("Byron", script.rows().get(0)[0]);

        service.disconnect();
        assertFalse(service.isConnected());
    }

    @Test
    void keepsRawValuesSeparateFromDisplayText() throws Exception {
        Path db = tempDir.resolve("raw.db");
        String longText = "x".repeat(500);
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db);
                Statement s = c.createStatement()) {
            s.execute("CREATE TABLE notes (note TEXT)");
            s.execute("INSERT INTO notes VALUES ('first" + "\n" + "second')");
            s.execute("INSERT INTO notes VALUES ('" + longText + "')");
        }

        DatabaseService service = new DatabaseService();
        service.connect(profile(db));
        QueryResult result = service.execute("SELECT note FROM notes");
        assertEquals("first\nsecond", result.rawRows().get(0)[0]);
        assertEquals(longText, result.rawRows().get(1)[0]);
        assertTrue(((String) result.rows().get(1)[0]).endsWith("…"));
        service.disconnect();
    }

    @Test
    void cancelledQueryDoesNotRunAnyStatements() throws Exception {
        Path db = tempDir.resolve("cancel.db");
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db);
                Statement s = c.createStatement()) {
            s.execute("CREATE TABLE people (name TEXT)");
            s.execute("INSERT INTO people VALUES ('Ada')");
        }

        DatabaseService service = new DatabaseService();
        service.connect(profile(db));

        QueryCancel cancel = new QueryCancel();
        cancel.cancel();
        QueryResult result = service.execute(
                "INSERT INTO people VALUES ('Ignored'); SELECT name FROM people", cancel);
        assertTrue(result.columns().isEmpty());
        assertEquals(0, result.totalUpdates());
        QueryResult after = service.execute("SELECT name FROM people");
        assertEquals(List.of("Ada"), after.rows().stream().map(row -> row[0]).toList());

        service.disconnect();
    }

    @Test
    void deletesRowsInTheDatabaseUsingPrimaryKeys() throws Exception {
        Path db = tempDir.resolve("delete.db");
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db);
                Statement s = c.createStatement()) {
            s.execute("CREATE TABLE items (id INTEGER PRIMARY KEY, name TEXT)");
            s.execute("INSERT INTO items VALUES (1, 'a'), (2, 'b'), (3, 'c')");
        }

        DatabaseService service = new DatabaseService();
        service.connect(profile(db));
        QueryResult result = service.execute("SELECT id, name FROM items");
        assertEquals(3, result.rows().size());
        assertNotNull(result.sourceTable());
        assertEquals("items", result.sourceTable().table());
        assertEquals(List.of("id"), result.sourceTable().keyColumns());

        assertEquals(1, service.deleteRows(result.sourceTable(), List.<Object[]>of(result.rawRows().get(1))));
        QueryResult after = service.execute("SELECT name FROM items ORDER BY id");
        assertEquals(List.of("a", "c"), after.rows().stream().map(row -> row[0]).toList());

        QueryResult withoutKey = service.execute("SELECT name FROM items");
        SQLException error = assertThrows(SQLException.class,
                () -> service.deleteRows(withoutKey.sourceTable(), List.<Object[]>of(withoutKey.rawRows().get(0))));
        assertTrue(error.getMessage().contains("primary key"));
        service.disconnect();
    }

    @Test
    void appliesUpdatesAndInsertsToTheDatabase() throws Exception {
        Path db = tempDir.resolve("edit.db");
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db);
                Statement s = c.createStatement()) {
            s.execute("CREATE TABLE items (id INTEGER PRIMARY KEY, name TEXT, qty INTEGER)");
            s.execute("INSERT INTO items VALUES (1, 'a', 1), (2, 'b', 2)");
        }

        DatabaseService service = new DatabaseService();
        service.connect(profile(db));
        QueryResult result = service.execute("SELECT id, name, qty FROM items ORDER BY id");
        QueryResult.SourceTable source = result.sourceTable();

        LinkedHashMap<String, Object> changed = new LinkedHashMap<>();
        changed.put("name", "Grace");
        changed.put("qty", 9);
        DatabaseService.RowUpdate update = new DatabaseService.RowUpdate(
                List.of("id"), List.of(result.rawRows().get(0)[0]), changed);
        LinkedHashMap<String, Object> added = new LinkedHashMap<>();
        added.put("name", "Ada");
        added.put("qty", 3);
        DatabaseService.RowInsert insert = new DatabaseService.RowInsert(added);

        assertEquals(2, service.applyChanges(source, List.of(update), List.of(insert)));
        QueryResult after = service.execute("SELECT id, name, qty FROM items ORDER BY id");
        assertEquals(List.of("Grace", "b", "Ada"), after.rows().stream().map(r -> r[1]).toList());
        assertEquals("9", after.rows().get(0)[2]);
        assertEquals("3", after.rows().get(2)[2]);
        service.disconnect();
    }

    @Test
    void rollsBackWhenOneChangeFails() throws Exception {
        Path db = tempDir.resolve("rollback.db");
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db);
                Statement s = c.createStatement()) {
            s.execute("CREATE TABLE items (id INTEGER PRIMARY KEY, name TEXT)");
            s.execute("INSERT INTO items VALUES (1, 'a')");
        }

        DatabaseService service = new DatabaseService();
        service.connect(profile(db));
        QueryResult result = service.execute("SELECT id, name FROM items");

        DatabaseService.RowUpdate good = new DatabaseService.RowUpdate(
                List.of("id"), List.of(1), Map.of("name", "changed"));
        LinkedHashMap<String, Object> bad = new LinkedHashMap<>();
        bad.put("no_such_column", "x");
        assertThrows(SQLException.class, () -> service.applyChanges(result.sourceTable(),
                List.of(good), List.of(new DatabaseService.RowInsert(bad))));
        assertEquals("a", service.execute("SELECT name FROM items").rows().get(0)[0]);
        service.disconnect();
    }

    @Test
    void onlyMapsResultsFromOneTable() throws Exception {
        Path db = tempDir.resolve("join.db");
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db);
                Statement s = c.createStatement()) {
            s.execute("CREATE TABLE people (id INTEGER PRIMARY KEY, name TEXT)");
            s.execute("CREATE TABLE notes (id INTEGER PRIMARY KEY, person_id INTEGER, note TEXT)");
            s.execute("INSERT INTO people VALUES (1, 'Ada')");
            s.execute("INSERT INTO notes VALUES (1, 1, 'hello')");
        }

        DatabaseService service = new DatabaseService();
        service.connect(profile(db));
        QueryResult joined = service.execute(
                "SELECT people.id, notes.note FROM people JOIN notes ON notes.person_id = people.id");
        assertNull(joined.sourceTable());
        service.disconnect();
    }

    @Test
    void listsColumnsWithPrimaryKeys() throws Exception {
        Path db = tempDir.resolve("columns.db");
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db);
                Statement s = c.createStatement()) {
            s.execute("CREATE TABLE people (id INTEGER PRIMARY KEY, name TEXT, note VARCHAR(50))");
        }

        DatabaseService service = new DatabaseService();
        service.connect(profile(db));
        TableInfo table = service.listTables().stream()
                .filter(t -> t.name().equals("people"))
                .findFirst()
                .orElseThrow();

        List<ColumnInfo> columns = service.listColumns(table);
        assertEquals(List.of("id", "name", "note"), columns.stream().map(ColumnInfo::name).toList());
        assertEquals(List.of(1, 2, 3), columns.stream().map(ColumnInfo::ordinal).toList());
        assertTrue(columns.get(0).primaryKey());
        assertFalse(columns.get(1).primaryKey());
        assertEquals("id  [INTEGER]  (PK)", columns.get(0).displayLabel());
        service.disconnect();
    }

    @Test
    void buildsDefaultDatabaseStatementsForEachDatabaseType() {
        assertEquals("USE `sales`",
                DatabaseService.defaultDatabaseStatement("sales", false, "MySQL"));
        assertEquals("SET search_path TO \"public\"",
                DatabaseService.defaultDatabaseStatement("public", true, "PostgreSQL"));
        assertEquals("USE \"sales\"",
                DatabaseService.defaultDatabaseStatement("sales", false, "Generic JDBC"));
        assertNull(DatabaseService.defaultDatabaseStatement("main", false, "SQLite"));
        assertNull(DatabaseService.defaultDatabaseStatement("", false, "MySQL"));
    }

    @Test
    void reportsUnknownDriverClass() {
        DatabaseService service = new DatabaseService();
        ConnectionProfile bad = new ConnectionProfile(
                "Bad", "Generic JDBC", "jdbc:nope://example", "", "", "no.such.Driver");
        SQLException error = assertThrows(SQLException.class, () -> service.connect(bad));
        assertTrue(error.getMessage().contains("not found"));
        assertFalse(service.isConnected());
    }

    private ConnectionProfile profile(Path db) {
        return new ConnectionProfile("Test", "SQLite", "jdbc:sqlite:" + db, "", "", "org.sqlite.JDBC");
    }
}
