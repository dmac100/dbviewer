package dbviewer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.sql.SQLException;

import org.junit.jupiter.api.Test;

class SshTunnelSessionTest {

    @Test
    void rewritesJdbcUrlToTheForwardedLocalPort() throws Exception {
        assertEquals("jdbc:mysql://127.0.0.1:45454/mydb?useSSL=false",
                SshTunnelSession.rewrite(
                        "jdbc:mysql://db.example.com:3306/mydb?useSSL=false", 45454, "MySQL"));
        assertEquals("jdbc:postgresql://127.0.0.1:12345/app",
                SshTunnelSession.rewrite(
                        "jdbc:postgresql://db.example.com/app", 12345, "PostgreSQL"));
    }

    @Test
    void rejectsUrlsWithoutNetworkHosts() {
        assertThrows(SQLException.class,
                () -> SshTunnelSession.rewrite("jdbc:sqlite:/tmp/test.db", 45454, "SQLite"));
    }

    @Test
    void connectionsAreUntunneledByDefault() {
        ConnectionProfile profile = new ConnectionProfile(
                "Test", "SQLite", "jdbc:sqlite:/tmp/test.db", "", "", "org.sqlite.JDBC");
        assertFalse(profile.ssh().enabled());
    }
}
