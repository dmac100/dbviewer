package dbviewer;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

class PastedDataTest {

    @Test
    void parsesTabSeparatedRowsAndIgnoresTheTrailingLineBreak() {
        List<String[]> rows = PastedData.parse("1\tAda\n2\tBob\n");

        assertEquals(2, rows.size());
        assertArrayEquals(new String[] {"1", "Ada"}, rows.get(0));
        assertArrayEquals(new String[] {"2", "Bob"}, rows.get(1));
    }

    @Test
    void keepsTrailingEmptyFields() {
        List<String[]> rows = PastedData.parse("1\t\t\n2\tBob\t");

        assertArrayEquals(new String[] {"1", "", ""}, rows.get(0));
        assertArrayEquals(new String[] {"2", "Bob", ""}, rows.get(1));
    }

    @Test
    void parsesQuotedCsvWithCommasAndLineBreaks() {
        List<String[]> rows = PastedData.parse(
                "1,\"Ada, Lovelace\",\"line one\nline two\"\n2,\"He said \"\"hi\"\"\",x\n");

        assertEquals(2, rows.size());
        assertArrayEquals(new String[] {"1", "Ada, Lovelace", "line one\nline two"}, rows.get(0));
        assertArrayEquals(new String[] {"2", "He said \"hi\"", "x"}, rows.get(1));
    }

    @Test
    void treatsTextWithoutDelimitersAsOneColumn() {
        List<String[]> rows = PastedData.parse("single value");

        assertEquals(1, rows.size());
        assertArrayEquals(new String[] {"single value"}, rows.get(0));
    }

    @Test
    void rejectsUnterminatedQuotes() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> PastedData.parse("\"unterminated"));

        assertTrue(error.getMessage().contains("unterminated"));
    }
}
