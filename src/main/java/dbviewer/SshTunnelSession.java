package dbviewer;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.Locale;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.jcraft.jsch.JSch;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.Session;

/** An SSH local port forward that survives for the lifetime of a JDBC connection. */
final class SshTunnelSession {

    private static final Pattern JDBC_URL = Pattern.compile(
            "^(jdbc:[^:]+://)([^/:?#]+)(?::(\\d+))?(.*)$", Pattern.CASE_INSENSITIVE);
    private static final int SSH_TIMEOUT_MILLIS = 15_000;

    private final Session session;
    private final int localPort;
    private final String localJdbcUrl;

    private SshTunnelSession(Session session, int localPort, String localJdbcUrl) {
        this.session = session;
        this.localPort = localPort;
        this.localJdbcUrl = localJdbcUrl;
    }

    /** Opens the SSH session and forwards a free local port to the database host. */
    static SshTunnelSession open(SshTunnel tunnel, String jdbcUrl, String dbType) throws SQLException {
        if (tunnel.host().isBlank() || tunnel.user().isBlank()) {
            throw new SQLException("SSH host and user are required for the tunnel.");
        }
        if (!tunnel.usePrivateKey() && tunnel.password().isBlank()) {
            throw new SQLException("Enter an SSH password or choose a private key file.");
        }

        int sshPort = parsePort(tunnel.port(), "SSH port");
        Remote remote = remoteOf(jdbcUrl, dbType);
        String knownHosts = knownHostsPath(tunnel);

        JSch jsch = new JSch();
        try {
            jsch.setKnownHosts(knownHosts);
            if (tunnel.usePrivateKey()) {
                String keyPath = expandHome(tunnel.privateKeyPath());
                if (!Files.isRegularFile(Path.of(keyPath))) {
                    throw new SQLException("SSH private key not found: " + keyPath);
                }
                byte[] passphrase = tunnel.keyPassphrase().isEmpty()
                        ? null : tunnel.keyPassphrase().getBytes(StandardCharsets.UTF_8);
                jsch.addIdentity(keyPath, passphrase);
            }

            Session session = jsch.getSession(tunnel.user(), tunnel.host(), sshPort);
            if (!tunnel.password().isBlank()) {
                session.setPassword(tunnel.password());
            }
            Properties config = new Properties();
            config.put("StrictHostKeyChecking", "yes");
            session.setConfig(config);
            session.connect(SSH_TIMEOUT_MILLIS);

            int localPort = session.setPortForwardingL(0, remote.host(), remote.port());
            return new SshTunnelSession(session, localPort, rewrite(jdbcUrl, localPort, dbType));
        } catch (JSchException e) {
            throw new SQLException("SSH tunnel to " + tunnel.host() + ":" + sshPort
                    + " failed: " + e.getMessage(), e);
        }
    }

    String localJdbcUrl() {
        return localJdbcUrl;
    }

    void close() {
        if (session == null || !session.isConnected()) {
            return;
        }
        try {
            session.delPortForwardingL(localPort);
        } catch (JSchException ignored) {
            // The session is being closed anyway.
        }
        session.disconnect();
    }

    /** Rewrites a single-host JDBC URL to use the local forwarded port. */
    static String rewrite(String jdbcUrl, int localPort, String dbType) throws SQLException {
        Matcher matcher = JDBC_URL.matcher(jdbcUrl);
        if (!matcher.matches()) {
            throw new SQLException("SSH tunneling requires a JDBC URL with a host and port, "
                    + "for example jdbc:mysql://db.example.com:3306/mydb.");
        }
        return matcher.group(1) + "127.0.0.1:" + localPort + matcher.group(4);
    }

    private static Remote remoteOf(String jdbcUrl, String dbType) throws SQLException {
        Matcher matcher = JDBC_URL.matcher(jdbcUrl);
        if (!matcher.matches() || matcher.group(2).isBlank()) {
            throw new SQLException("SSH tunneling requires a JDBC URL with a host and port, "
                    + "for example jdbc:mysql://db.example.com:3306/mydb.");
        }
        int port = matcher.group(3) == null
                ? defaultPort(jdbcUrl, dbType) : parsePort(matcher.group(3), "remote database port");
        return new Remote(matcher.group(2), port);
    }

    private static int defaultPort(String jdbcUrl, String dbType) throws SQLException {
        String candidate = jdbcUrl.toLowerCase(Locale.ROOT);
        if (candidate.startsWith("jdbc:mysql:") || candidate.startsWith("jdbc:mariadb:")
                || "mysql".equalsIgnoreCase(dbType)) {
            return 3306;
        }
        if (candidate.startsWith("jdbc:postgresql:") || candidate.startsWith("jdbc:postgres:")
                || "postgresql".equalsIgnoreCase(dbType)) {
            return 5432;
        }
        if (candidate.startsWith("jdbc:sqlserver:")) {
            return 1433;
        }
        throw new SQLException("Specify the remote database port in the JDBC URL when using SSH.");
    }

    private static int parsePort(String value, String label) throws SQLException {
        try {
            int port = Integer.parseInt(value.trim());
            if (port < 1 || port > 65535) {
                throw new NumberFormatException();
            }
            return port;
        } catch (NumberFormatException e) {
            throw new SQLException(label + " must be a number between 1 and 65535.");
        }
    }

    private static String knownHostsPath(SshTunnel tunnel) throws SQLException {
        String path = tunnel.knownHostsPath().isBlank()
                ? Path.of(System.getProperty("user.home"), ".ssh", "known_hosts").toString()
                : expandHome(tunnel.knownHostsPath());
        if (!Files.isRegularFile(Path.of(path))) {
            throw new SQLException("SSH known hosts file not found: " + path
                    + ". Add the host key first, e.g. ssh-keyscan " + tunnel.host()
                    + " >> ~/.ssh/known_hosts.");
        }
        return path;
    }

    private static String expandHome(String path) {
        if (path.equals("~")) {
            return System.getProperty("user.home");
        }
        if (path.startsWith("~/")) {
            return Path.of(System.getProperty("user.home"), path.substring(2)).toString();
        }
        return path;
    }

    private record Remote(String host, int port) {
    }
}
