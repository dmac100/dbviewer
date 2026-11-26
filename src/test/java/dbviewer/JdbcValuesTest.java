package dbviewer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Date;
import java.sql.Time;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.OffsetTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

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
    void acceptsCommonDateAndTimeFormats() {
        Locale previous = Locale.getDefault();
        try {
            Locale.setDefault(Locale.US);

            assertEquals(Date.valueOf("2026-01-02"), JdbcValues.parse("1/2/2026", Types.DATE));
            assertEquals(Date.valueOf("2026-01-02"),
                    JdbcValues.parse("2026-01-02T09:30:00", Types.DATE));
            assertEquals(Time.valueOf("15:04:00"), JdbcValues.parse("3:04 PM", Types.TIME));
            assertEquals(Timestamp.valueOf("2026-01-02 09:30:00"),
                    JdbcValues.parse("2026-01-02T09:30", Types.TIMESTAMP));
            assertEquals(Timestamp.valueOf("2026-01-02 15:04:05.123"),
                    JdbcValues.parse("1/2/2026 3:04:05.123 PM", Types.TIMESTAMP));
            assertEquals(OffsetDateTime.parse("2026-01-02T03:04:05+02:00"),
                    JdbcValues.parse("2026-01-02 03:04:05+02:00", Types.TIMESTAMP_WITH_TIMEZONE));
            assertEquals(OffsetTime.parse("15:04:05+02:00"),
                    JdbcValues.parse("3:04:05 PM +02:00", Types.TIME_WITH_TIMEZONE));
        } finally {
            Locale.setDefault(previous);
        }
    }

    @Test
    void usesTheCurrentLocaleForNumericDateOrder() {
        Locale previous = Locale.getDefault();
        try {
            Locale.setDefault(Locale.US);
            assertEquals(Date.valueOf("2026-01-02"), JdbcValues.parse("1/2/2026", Types.DATE));
            assertEquals(Timestamp.valueOf("2026-01-02 09:30:00"),
                    JdbcValues.parse("1/2/2026 09:30", Types.TIMESTAMP));

            Locale.setDefault(Locale.UK);
            assertEquals(Date.valueOf("2026-02-01"), JdbcValues.parse("1/2/2026", Types.DATE));
            assertEquals(Timestamp.valueOf("2026-02-01 09:30:00"),
                    JdbcValues.parse("1/2/2026 09:30", Types.TIMESTAMP));
        } finally {
            Locale.setDefault(previous);
        }
    }

    @Test
    void usesTheCurrentLocaleForMonthNamesAndAmPm() {
        Locale previous = Locale.getDefault();
        try {
            Locale.setDefault(Locale.GERMANY);

            String date = DateTimeFormatter.ofPattern("MMM d, uuuu", Locale.GERMANY)
                    .format(LocalDate.of(2026, 1, 2));
            assertEquals(Date.valueOf("2026-01-02"), JdbcValues.parse(date, Types.DATE));

            String time = DateTimeFormatter.ofPattern("h:m a", Locale.GERMANY)
                    .format(LocalDateTime.of(2026, 1, 2, 15, 4));
            assertEquals(Time.valueOf("15:04:00"), JdbcValues.parse(time, Types.TIME));
        } finally {
            Locale.setDefault(previous);
        }
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
