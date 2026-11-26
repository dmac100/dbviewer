package dbviewer;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.StringWriter;
import java.util.List;

import org.junit.jupiter.api.Test;

class CsvTest {

    @Test
    void escapesFieldsThatNeedQuoting() {
        assertEquals("", Csv.escape(null));
        assertEquals("", Csv.escape(""));
        assertEquals("plain", Csv.escape("plain"));
        assertEquals("\"a,b\"", Csv.escape("a,b"));
        assertEquals("\"a\"\"b\"", Csv.escape("a\"b"));
        assertEquals("\"line1\nline2\"", Csv.escape("line1\nline2"));
        assertEquals("\"a\r\nb\"", Csv.escape("a\r\nb"));
    }

    @Test
    void formatsRecords() {
        assertEquals("id,name,note", Csv.line(List.of("id", "name", "note")));
        assertEquals("1,Ada,\"said \"\"hi\"\"\"",
                Csv.line(new String[] {"1", "Ada", "said \"hi\""}));
        assertEquals("", Csv.line(List.of()));
    }

    @Test
    void writesHeaderAndRows() throws Exception {
        StringWriter out = new StringWriter();
        Csv.write(out, List.of("id", "name"),
                List.of(new String[] {"1", "Ada"}, new String[] {"2", "Grace, H"}));
        assertEquals("id,name\n1,Ada\n2,\"Grace, H\"\n", out.toString());
    }
}
