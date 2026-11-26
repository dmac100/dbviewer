package dbviewer;

import java.io.IOException;
import java.io.Writer;
import java.util.List;

/** CSV formatting for query results. Callers join the returned records with newlines. */
public final class Csv {

    private Csv() {
    }

    /** Returns one CSV record, quoting fields that contain commas, quotes or line breaks. */
    public static String line(List<String> fields) {
        StringBuilder record = new StringBuilder();
        for (int i = 0; i < fields.size(); i++) {
            if (i > 0) {
                record.append(',');
            }
            record.append(escape(fields.get(i)));
        }
        return record.toString();
    }

    /** Returns one CSV record from an array of fields. */
    public static String line(String[] fields) {
        StringBuilder record = new StringBuilder();
        for (int i = 0; i < fields.length; i++) {
            if (i > 0) {
                record.append(',');
            }
            record.append(escape(fields[i]));
        }
        return record.toString();
    }

    /** Writes a header record followed by one record per row, each ending with a newline. */
    public static void write(Writer out, List<String> columns, List<String[]> rows)
            throws IOException {
        out.write(line(columns));
        out.write('\n');
        for (String[] row : rows) {
            out.write(line(row));
            out.write('\n');
        }
    }

    /** Escapes a single field according to RFC 4180. */
    public static String escape(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        boolean quote = false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == ',' || c == '"' || c == '\n' || c == '\r') {
                quote = true;
                break;
            }
        }
        if (!quote) {
            return value;
        }
        StringBuilder escaped = new StringBuilder(value.length() + 2).append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '"') {
                escaped.append('"');
            }
            escaped.append(c);
        }
        return escaped.append('"').toString();
    }
}
