package dbviewer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

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
