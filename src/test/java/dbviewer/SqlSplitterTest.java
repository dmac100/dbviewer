package dbviewer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

class SqlSplitterTest {

    @Test
    void splitsSimpleStatements() {
        assertEquals(List.of("SELECT 1", "SELECT 2"), SqlSplitter.split("SELECT 1; SELECT 2;"));
    }

    @Test
    void ignoresSemicolonsInQuotesAndComments() {
        String script = "SELECT 'a;b'; -- skip; this\n SELECT \"c;d\" ; /* x;y */ SELECT 3";
        assertEquals(
                List.of("SELECT 'a;b'", "-- skip; this\n SELECT \"c;d\"", "/* x;y */ SELECT 3"),
                SqlSplitter.split(script));
    }

    @Test
    void skipsCommentOnlyStatements() {
        assertEquals(List.of("SELECT 1"), SqlSplitter.split("SELECT 1; -- trailing comment"));
        assertTrue(SqlSplitter.split("  ; ; /* nothing */ ").isEmpty());
    }

    @Test
    void locatesTheStatementAtTheCursor() {
        var statements = SqlSplitter.splitWithPositions("SELECT 1;\n SELECT 2;");
        assertEquals(2, statements.size());
        assertEquals(SqlSplitter.statementAt(statements, 3), statements.get(0));
        assertEquals(SqlSplitter.statementAt(statements, 12), statements.get(1));
        // The caret just after a semicolon belongs to the statement that ended there.
        assertEquals(SqlSplitter.statementAt(statements, 8), statements.get(0));
    }
}
