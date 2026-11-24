package dbviewer;

/** SSH tunnel settings for a JDBC connection, or {@link #DISABLED}. */
public record SshTunnel(
        boolean enabled,
        String host,
        String port,
        String user,
        String password,
        String privateKeyPath,
        String keyPassphrase,
        String knownHostsPath) {

    public static final SshTunnel DISABLED = new SshTunnel(
            false, "", "22", "", "", "", "", "");

    public SshTunnel {
        host = nullToEmpty(host);
        port = nullToEmpty(port);
        user = nullToEmpty(user);
        password = nullToEmpty(password);
        privateKeyPath = nullToEmpty(privateKeyPath);
        keyPassphrase = nullToEmpty(keyPassphrase);
        knownHostsPath = nullToEmpty(knownHostsPath);
        if (port.isBlank()) {
            port = "22";
        }
    }

    /** Creates an enabled tunnel with sensible defaults for the optional fields. */
    public static SshTunnel enabled(String host, String port, String user) {
        return new SshTunnel(true, host, port, user, "", "", "", "");
    }

    public int portNumber() throws NumberFormatException {
        return Integer.parseInt(port.trim());
    }

    public boolean usePrivateKey() {
        return !privateKeyPath.isBlank();
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
