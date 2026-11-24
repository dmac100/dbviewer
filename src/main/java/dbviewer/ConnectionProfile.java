package dbviewer;

/**
 * Everything needed to open a JDBC connection. Blank strings are used instead of
 * {@code null} so the UI code can treat all fields uniformly.
 */
public record ConnectionProfile(
        String name,
        String dbType,
        String jdbcUrl,
        String user,
        String password,
        String driverClass,
        SshTunnel ssh) {

    /** Convenience constructor for connections without an SSH tunnel. */
    public ConnectionProfile(
            String name, String dbType, String jdbcUrl,
            String user, String password, String driverClass) {
        this(name, dbType, jdbcUrl, user, password, driverClass, SshTunnel.DISABLED);
    }

    public ConnectionProfile {
        name = nullToEmpty(name);
        dbType = nullToEmpty(dbType);
        jdbcUrl = nullToEmpty(jdbcUrl);
        user = nullToEmpty(user);
        password = nullToEmpty(password);
        driverClass = nullToEmpty(driverClass);
        ssh = ssh == null ? SshTunnel.DISABLED : ssh;
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
