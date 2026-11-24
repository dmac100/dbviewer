package dbviewer;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Date;
import java.sql.Time;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.OffsetDateTime;
import java.time.OffsetTime;
import java.time.format.DateTimeParseException;
import java.util.Locale;

/** Converts text typed into an editable result cell back into a JDBC value. */
final class JdbcValues {

    /** Typed by the user to store an explicit SQL NULL in a cell. */
    static final String NULL_TEXT = "<NULL>";

    /** Typed by the user to store an empty string, which is distinct from an omitted value. */
    static final String EMPTY_TEXT = "''";

    private JdbcValues() {
    }

    /**
     * Parses one cell's text for a column of {@code sqlType}. An empty text means NULL for
     * non-character columns and an empty string for character columns.
     */
    static Object parse(String text, int sqlType) {
        if (text == null) {
            return null;
        }
        if (text.equalsIgnoreCase(NULL_TEXT)) {
            return null;
        }
        if (text.equals(EMPTY_TEXT)) {
            return "";
        }
        if (text.isEmpty()) {
            return isCharacter(sqlType) ? "" : null;
        }

        String value = text.trim();
        try {
            return switch (sqlType) {
                case Types.INTEGER -> Integer.valueOf(value);
                case Types.SMALLINT -> Short.valueOf(value);
                case Types.TINYINT -> Byte.valueOf(value);
                case Types.BIGINT -> Long.valueOf(value);
                case Types.REAL -> Float.valueOf(value);
                case Types.FLOAT, Types.DOUBLE -> Double.valueOf(value);
                case Types.NUMERIC, Types.DECIMAL -> new BigDecimal(value);
                case Types.BOOLEAN -> parseBoolean(value);
                case Types.BIT -> parseBoolean(value);
                case Types.DATE -> Date.valueOf(value);
                case Types.TIME -> Time.valueOf(value);
                case Types.TIME_WITH_TIMEZONE -> OffsetTime.parse(value);
                case Types.TIMESTAMP -> Timestamp.valueOf(value);
                case Types.TIMESTAMP_WITH_TIMEZONE -> OffsetDateTime.parse(value);
                case Types.BINARY, Types.VARBINARY, Types.LONGVARBINARY, Types.BLOB ->
                        value.getBytes(StandardCharsets.UTF_8);
                default -> text;
            };
        } catch (IllegalArgumentException | DateTimeParseException e) {
            throw new IllegalArgumentException(
                    "'" + text + "' is not a valid " + typeName(sqlType) + " value.");
        }
    }

    private static Boolean parseBoolean(String value) {
        return switch (value.toLowerCase(Locale.ROOT)) {
            case "true", "yes", "1" -> Boolean.TRUE;
            case "false", "no", "0" -> Boolean.FALSE;
            default -> throw new IllegalArgumentException(
                    "'" + value + "' is not a valid boolean value (use true or false).");
        };
    }

    private static boolean isCharacter(int sqlType) {
        return switch (sqlType) {
            case Types.CHAR, Types.VARCHAR, Types.LONGVARCHAR,
                    Types.NCHAR, Types.NVARCHAR, Types.CLOB, Types.NCLOB -> true;
            default -> false;
        };
    }

    private static String typeName(int sqlType) {
        return switch (sqlType) {
            case Types.INTEGER -> "integer";
            case Types.SMALLINT -> "small integer";
            case Types.TINYINT -> "tiny integer";
            case Types.BIGINT -> "big integer";
            case Types.REAL, Types.FLOAT, Types.DOUBLE -> "number";
            case Types.NUMERIC, Types.DECIMAL -> "decimal number";
            case Types.BOOLEAN, Types.BIT -> "boolean";
            case Types.DATE -> "date (yyyy-mm-dd)";
            case Types.TIME -> "time (hh:mm:ss)";
            case Types.TIME_WITH_TIMEZONE -> "time with time zone";
            case Types.TIMESTAMP -> "timestamp (yyyy-mm-dd hh:mm:ss)";
            case Types.TIMESTAMP_WITH_TIMEZONE -> "timestamp with time zone";
            default -> "value";
        };
    }
}
