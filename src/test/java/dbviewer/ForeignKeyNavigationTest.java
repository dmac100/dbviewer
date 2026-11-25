package dbviewer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

class ForeignKeyNavigationTest {

    @TempDir
    Path tempDir;

    @Test
    void followsDeclaredForeignKey() throws Exception {
        Path db = tempDir.resolve("foreign.db");
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db);
                Statement s = c.createStatement()) {
            s.execute("CREATE TABLE people (id INTEGER PRIMARY KEY, name TEXT)");
            s.execute("CREATE TABLE notes (id INTEGER PRIMARY KEY, person_id INTEGER, note TEXT,"
                    + " FOREIGN KEY (person_id) REFERENCES people(id))");
            s.execute("INSERT INTO people VALUES (1, 'Ada')");
            s.execute("INSERT INTO notes VALUES (10, 1, 'hello')");
        }

        DatabaseService service = connect(db);
        TableInfo notes = table(service, "notes");
        List<ForeignKeyReference> keys = service.importedKeys(notes);
        assertEquals(1, keys.size());
        ForeignKeyReference key = keys.get(0);
        assertEquals("people", key.referencedTable().name());
        assertEquals(List.of("person_id"), key.foreignColumns());
        assertEquals(List.of("id"), key.referencedColumns());
        assertTrue(key.hasColumn("PERSON_ID"));
        assertFalse(key.hasColumn("note"));

        QueryResult result = service.execute("SELECT id, person_id, note FROM notes");
        String sql = service.referencedRowsSql(key, result.sourceTable(), result.rawRows().get(0));
        assertEquals("SELECT * FROM \"people\" WHERE \"id\" = 1", sql);
        QueryResult target = service.execute(sql);
        assertEquals(List.of("Ada"), target.rows().stream().map(row -> row[1]).toList());
        service.disconnect();
    }

    @Test
    void followsCompositeForeignKeyInKeyOrder() throws Exception {
        Path db = tempDir.resolve("composite.db");
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db);
                Statement s = c.createStatement()) {
            s.execute("CREATE TABLE pairs (a INTEGER, b INTEGER, label TEXT, PRIMARY KEY (a, b))");
            s.execute("CREATE TABLE links (id INTEGER PRIMARY KEY, pa INTEGER, pb INTEGER,"
                    + " FOREIGN KEY (pa, pb) REFERENCES pairs(a, b))");
            s.execute("INSERT INTO pairs VALUES (2, 3, 'two-three')");
            s.execute("INSERT INTO links VALUES (1, 2, 3)");
        }

        DatabaseService service = connect(db);
        ForeignKeyReference key = service.importedKeys(table(service, "links")).get(0);
        assertEquals(List.of("pa", "pb"), key.foreignColumns());
        assertEquals(List.of("a", "b"), key.referencedColumns());

        QueryResult result = service.execute("SELECT pa, pb FROM links");
        String sql = service.referencedRowsSql(key, result.sourceTable(), result.rawRows().get(0));
        assertEquals("SELECT * FROM \"pairs\" WHERE \"a\" = 2 AND \"b\" = 3", sql);
        assertEquals("two-three", service.execute(sql).rows().get(0)[2]);
        service.disconnect();
    }

    @Test
    void infersTablesFromColumnNamesWithoutConstraints() throws Exception {
        Path db = tempDir.resolve("inferred.db");
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db);
                Statement s = c.createStatement()) {
            s.execute("CREATE TABLE owners (id INTEGER PRIMARY KEY, name TEXT)");
            s.execute("CREATE TABLE teams (team_id INTEGER PRIMARY KEY, title TEXT)");
            s.execute("CREATE TABLE boxes (id INTEGER PRIMARY KEY, owner_id INTEGER, team_id INTEGER,"
                    + " label TEXT)");
            s.execute("INSERT INTO owners VALUES (1, 'Grace')");
            s.execute("INSERT INTO boxes VALUES (5, 1, 9, 'box')");
        }

        DatabaseService service = connect(db);
        TableInfo boxes = table(service, "boxes");
        List<ForeignKeyReference> ownerTargets = service.candidateReferences(boxes, "owner_id");
        assertEquals(1, ownerTargets.size());
        ForeignKeyReference owner = ownerTargets.get(0);
        assertEquals("owners", owner.referencedTable().name());
        assertEquals(List.of("id"), owner.referencedColumns());

        QueryResult result = service.execute("SELECT * FROM boxes");
        String sql = service.referencedRowsSql(owner, result.sourceTable(), result.rawRows().get(0));
        assertEquals("Grace", service.execute(sql).rows().get(0)[1]);

        List<ForeignKeyReference> teamTargets = service.candidateReferences(boxes, "team_id");
        assertEquals(1, teamTargets.size());
        assertEquals("teams", teamTargets.get(0).referencedTable().name());
        assertEquals(List.of("team_id"), teamTargets.get(0).referencedColumns());
        assertTrue(service.candidateReferences(boxes, "label").isEmpty());
        service.disconnect();
    }

    @Test
    void quotesValuesAndHandlesNullForeignKeys() throws Exception {
        assertEquals("'O''Brien'", DatabaseService.sqlLiteral("O'Brien"));
        assertEquals("7", DatabaseService.sqlLiteral(7));
        assertEquals("NULL", DatabaseService.sqlLiteral(null));
        assertEquals("X'0aff'", DatabaseService.sqlLiteral(new byte[] {0x0a, (byte) 0xff}));

        Path db = tempDir.resolve("null.db");
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db);
                Statement s = c.createStatement()) {
            s.execute("CREATE TABLE parents (id INTEGER PRIMARY KEY, name TEXT)");
            s.execute("CREATE TABLE children (id INTEGER PRIMARY KEY, parent_id INTEGER,"
                    + " FOREIGN KEY (parent_id) REFERENCES parents(id))");
            s.execute("INSERT INTO children VALUES (1, NULL)");
        }

        DatabaseService service = connect(db);
        ForeignKeyReference key = service.importedKeys(table(service, "children")).get(0);
        QueryResult result = service.execute("SELECT id, parent_id FROM children");
        String sql = service.referencedRowsSql(key, result.sourceTable(), result.rawRows().get(0));
        assertEquals("SELECT * FROM \"parents\" WHERE \"id\" IS NULL", sql);

        QueryResult.SourceTable withoutKey = service.execute("SELECT id FROM children").sourceTable();
        SQLException missing = assertThrows(SQLException.class,
                () -> service.referencedRowsSql(key, withoutKey, new Object[] {1}));
        assertTrue(missing.getMessage().contains("must include"));
        service.disconnect();
    }

    private DatabaseService connect(Path db) throws SQLException {
        DatabaseService service = new DatabaseService();
        service.connect(new ConnectionProfile("Test", "SQLite", "jdbc:sqlite:" + db,
                "", "", "org.sqlite.JDBC"));
        return service;
    }

    private TableInfo table(DatabaseService service, String name) throws SQLException {
        return service.listTables().stream()
                .filter(t -> t.name().equals(name))
                .findFirst().orElseThrow();
    }
}
