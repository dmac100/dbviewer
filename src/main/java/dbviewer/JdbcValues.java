package dbviewer;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Date;
import java.sql.Time;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.OffsetTime;
import java.time.ZoneOffset;
import java.time.chrono.IsoChronology;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;
import java.time.format.DecimalStyle;
import java.time.format.FormatStyle;
import java.time.temporal.ChronoField;
import java.time.temporal.TemporalQuery;
import java.util.List;
import java.util.Locale;

/** Converts text typed into an editable result cell back into a JDBC value. */
final class JdbcValues {

    /** Text used to store an explicit SQL NULL in a cell. */
    static final String NULL_TEXT = CellFormatter.NULL_TEXT;

    /** Text used to store an empty string, which is distinct from SQL NULL. */
    static final String EMPTY_TEXT = CellFormatter.EMPTY_TEXT;

    private static List<DateTimeFormatter> dateFormats() {
        return List.of(
                dateFormat("uuuu-M-d"),
                localizedNumericDateFormat('/'),
                localizedNumericDateFormat('.'),
                localizedNumericDateFormat('-'),
                dateFormat("MMM d, uuuu"),
                dateFormat("d MMM uuuu"));
    }

    private static List<DateTimeFormatter> timeFormats() {
        return List.of(
                timeFormat("H:m[:s]"),
                twelveHourTimeFormat("h:m[:s]"));
    }

    private static List<DateTimeFormatter> timeWithOffsetFormats() {
        return List.of(
                offsetTimeFormat("H:m[:s]"),
                twelveHourOffsetTimeFormat("h:m[:s]"));
    }

    private static List<DateTimeFormatter> timestampFormats() {
        return List.of(
                timeFormat("uuuu-M-d H:m[:s]"),
                timeFormat("uuuu-M-d'T'H:m[:s]"),
                twelveHourTimeFormat("uuuu-M-d h:m[:s]"),
                timeFormat(localizedNumericPattern('/') + " H:m[:s]"),
                twelveHourTimeFormat(localizedNumericPattern('/') + " h:m[:s]"),
                timeFormat(localizedNumericPattern('.') + " H:m[:s]"),
                twelveHourTimeFormat(localizedNumericPattern('.') + " h:m[:s]"),
                twelveHourTimeFormat("MMM d, uuuu h:m[:s]"));
    }

    private JdbcValues() {
    }

    /**
     * Parses one cell's text for a column of {@code sqlType}. A blank text stores an empty string;
     * SQL NULL has its own marker, normally set from the results context menu. Dates and times
     * accept ISO values as well as the common slash, 12-hour and space-separated forms.
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
            return "";
        }

        String value = text.trim();
        try {
            Object parsed = switch (sqlType) {
                case Types.INTEGER -> Integer.valueOf(value);
                case Types.SMALLINT -> Short.valueOf(value);
                case Types.TINYINT -> Byte.valueOf(value);
                case Types.BIGINT -> Long.valueOf(value);
                case Types.REAL -> Float.valueOf(value);
                case Types.FLOAT, Types.DOUBLE -> Double.valueOf(value);
                case Types.NUMERIC, Types.DECIMAL -> new BigDecimal(value);
                case Types.BOOLEAN -> parseBoolean(value);
                case Types.BIT -> parseBoolean(value);
                case Types.DATE -> dateValue(value);
                case Types.TIME -> timeValue(value);
                case Types.TIME_WITH_TIMEZONE -> offsetTimeValue(value);
                case Types.TIMESTAMP -> timestampValue(value);
                case Types.TIMESTAMP_WITH_TIMEZONE -> offsetDateTimeValue(value);
                case Types.BINARY, Types.VARBINARY, Types.LONGVARBINARY, Types.BLOB ->
                        value.getBytes(StandardCharsets.UTF_8);
                default -> text;
            };
            if (parsed == null) {
                throw new IllegalArgumentException();
            }
            return parsed;
        } catch (IllegalArgumentException | DateTimeParseException e) {
            throw new IllegalArgumentException(
                    "'" + text + "' is not a valid " + typeName(sqlType) + " value.");
        }
    }

    private static Boolean parseBoolean(String value) {
        return switch (value.toLowerCase(Locale.ROOT)) {
            case "true", "yes", "1" -> Boolean.TRUE;
            case "false", "no", "0" -> Boolean.FALSE;
            default -> throw new IllegalArgumentException();
        };
    }

    private static Date dateValue(String value) {
        LocalDate date = localDate(value);
        if (date == null) {
            LocalDateTime dateTime = localDateTime(value);
            date = dateTime == null ? null : dateTime.toLocalDate();
        }
        return date == null ? null : Date.valueOf(date);
    }

    private static Time timeValue(String value) {
        for (DateTimeFormatter format : timeFormats()) {
            LocalTime time = tryParse(value, format, LocalTime::from);
            if (time != null) {
                return Time.valueOf(time);
            }
        }
        LocalDateTime dateTime = localDateTime(value);
        return dateTime == null ? null : Time.valueOf(dateTime.toLocalTime());
    }

    private static Timestamp timestampValue(String value) {
        LocalDateTime dateTime = localDateTime(value);
        return dateTime == null ? null : Timestamp.valueOf(dateTime);
    }

    private static OffsetTime offsetTimeValue(String value) {
        OffsetTime time = tryParse(value, DateTimeFormatter.ISO_OFFSET_TIME, OffsetTime::from);
        if (time != null) {
            return time;
        }
        for (DateTimeFormatter format : timeWithOffsetFormats()) {
            time = tryParse(value, format, OffsetTime::from);
            if (time != null) {
                return time;
            }
        }
        LocalTime local = localTime(value);
        return local == null ? null : OffsetTime.of(local, ZoneOffset.UTC);
    }

    private static OffsetDateTime offsetDateTimeValue(String value) {
        OffsetDateTime dateTime = tryParse(
                value, DateTimeFormatter.ISO_OFFSET_DATE_TIME, OffsetDateTime::from);
        if (dateTime == null) {
            dateTime = tryParse(value.replace(' ', 'T'),
                    DateTimeFormatter.ISO_OFFSET_DATE_TIME, OffsetDateTime::from);
        }
        if (dateTime != null) {
            return dateTime;
        }
        LocalDateTime local = localDateTime(value);
        return local == null ? null : local.atOffset(ZoneOffset.UTC);
    }

    private static LocalDate localDate(String value) {
        for (DateTimeFormatter format : dateFormats()) {
            LocalDate date = tryParse(value, format, LocalDate::from);
            if (date != null) {
                return date;
            }
        }
        return null;
    }

    private static LocalTime localTime(String value) {
        for (DateTimeFormatter format : timeFormats()) {
            LocalTime time = tryParse(value, format, LocalTime::from);
            if (time != null) {
                return time;
            }
        }
        LocalDateTime dateTime = localDateTime(value);
        return dateTime == null ? null : dateTime.toLocalTime();
    }

    private static LocalDateTime localDateTime(String value) {
        for (DateTimeFormatter format : timestampFormats()) {
            LocalDateTime dateTime = tryParse(value, format, LocalDateTime::from);
            if (dateTime != null) {
                return dateTime;
            }
        }
        LocalDate date = localDate(value);
        if (date != null) {
            return date.atStartOfDay();
        }
        OffsetDateTime offset = tryParse(
                value, DateTimeFormatter.ISO_OFFSET_DATE_TIME, OffsetDateTime::from);
        if (offset == null) {
            offset = tryParse(value.replace(' ', 'T'),
                    DateTimeFormatter.ISO_OFFSET_DATE_TIME, OffsetDateTime::from);
        }
        return offset == null ? null : offset.toLocalDateTime();
    }

    private static DateTimeFormatter localizedNumericDateFormat(char separator) {
        return dateFormat(localizedNumericPattern(separator));
    }

    /**
     * Builds a numeric date pattern in the order used by the current locale's short date format.
     * This makes an ambiguous value like 1/2/2025 mean January 2 in month-first locales and
     * February 1 in day-first locales.
     */
    private static String localizedNumericPattern(char separator) {
        String localized = DateTimeFormatterBuilder.getLocalizedDateTimePattern(
                FormatStyle.SHORT, null, IsoChronology.INSTANCE, Locale.getDefault());
        StringBuilder pattern = new StringBuilder();
        for (int i = 0; i < localized.length();) {
            char field = localized.charAt(i);
            String part = switch (field) {
                case 'd' -> "d";
                case 'M', 'L' -> "M";
                case 'y' -> "uuuu";
                default -> null;
            };
            if (part == null) {
                i++;
                continue;
            }
            if (pattern.length() > 0) {
                pattern.append(separator);
            }
            pattern.append(part);
            while (i < localized.length()
                    && Character.toLowerCase(localized.charAt(i)) == Character.toLowerCase(field)) {
                i++;
            }
        }
        return pattern.length() == 0 ? "M" + separator + "d" + separator + "uuuu"
                : pattern.toString();
    }

    private static DateTimeFormatter dateFormat(String pattern) {
        return DateTimeFormatter.ofPattern(pattern, Locale.getDefault())
                .withDecimalStyle(DecimalStyle.STANDARD);
    }

    private static DateTimeFormatter timeFormat(String pattern) {
        return new DateTimeFormatterBuilder()
                .appendPattern(pattern)
                .optionalStart()
                .appendFraction(ChronoField.NANO_OF_SECOND, 1, 9, true)
                .optionalEnd()
                .toFormatter(Locale.getDefault())
                .withDecimalStyle(DecimalStyle.STANDARD);
    }

    private static DateTimeFormatter twelveHourTimeFormat(String pattern) {
        return new DateTimeFormatterBuilder()
                .appendPattern(pattern)
                .optionalStart()
                .appendFraction(ChronoField.NANO_OF_SECOND, 1, 9, true)
                .optionalEnd()
                .appendLiteral(' ')
                .appendPattern("a")
                .toFormatter(Locale.getDefault())
                .withDecimalStyle(DecimalStyle.STANDARD);
    }

    private static DateTimeFormatter offsetTimeFormat(String pattern) {
        return new DateTimeFormatterBuilder()
                .appendPattern(pattern)
                .optionalStart()
                .appendFraction(ChronoField.NANO_OF_SECOND, 1, 9, true)
                .optionalEnd()
                .appendLiteral(' ')
                .appendPattern("XXX")
                .toFormatter(Locale.getDefault())
                .withDecimalStyle(DecimalStyle.STANDARD);
    }

    private static DateTimeFormatter twelveHourOffsetTimeFormat(String pattern) {
        return new DateTimeFormatterBuilder()
                .appendPattern(pattern)
                .optionalStart()
                .appendFraction(ChronoField.NANO_OF_SECOND, 1, 9, true)
                .optionalEnd()
                .appendLiteral(' ')
                .appendPattern("a")
                .appendLiteral(' ')
                .appendPattern("XXX")
                .toFormatter(Locale.getDefault())
                .withDecimalStyle(DecimalStyle.STANDARD);
    }

    private static <T> T tryParse(String value, DateTimeFormatter format, TemporalQuery<T> query) {
        try {
            return format.parse(value, query);
        } catch (DateTimeParseException e) {
            return null;
        }
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
            case Types.DATE -> "date";
            case Types.TIME -> "time";
            case Types.TIME_WITH_TIMEZONE -> "time with time zone";
            case Types.TIMESTAMP -> "timestamp";
            case Types.TIMESTAMP_WITH_TIMEZONE -> "timestamp with time zone";
            default -> "value";
        };
    }
}
