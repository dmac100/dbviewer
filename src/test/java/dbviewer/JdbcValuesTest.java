package dbviewer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Date;
import java.sql.Types;

import org.junit.jupiter.api.Test;

class JdbcValuesTest {

    @Test
    void parsesNumbersBooleansDatesAndNulls() {
        assertEquals(42, JdbcValues.parse("42", Types.INTEGER));
        assertEquals(3L, JdbcValues.parse("3", Types.BIGINT));
        assertEquals(2.5, JdbcValues.parse("2.5", Types.DOUBLE));
        assertEquals(Boolean.TRUE, JdbcValues.parse("true", Types.BOOLEAN));
        assertEquals(Date.valueOf("2026-01-02"), JdbcValues.parse("2026-01-02", Types.DATE));
        assertEquals("", JdbcValues.parse("", Types.VARCHAR));
        assertEquals("", JdbcValues.parse(JdbcValues.EMPTY_TEXT, Types.VARCHAR));
        assertNull(JdbcValues.parse(JdbcValues.NULL_TEXT, Types.VARCHAR));
        assertNull(JdbcValues.parse("", Types.INTEGER));
    }

    @Test
    void reportsInvalidValues() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> JdbcValues.parse("abc", Types.INTEGER));
        assertTrue(error.getMessage().contains("integer"));
        assertThrows(IllegalArgumentException.class,
                () -> JdbcValues.parse("maybe", Types.BOOLEAN));
    }
}
