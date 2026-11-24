package dbviewer;

import java.sql.SQLException;

import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Dialog;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.FileDialog;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.MessageBox;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Text;

/** Modal dialog for entering JDBC connection details. */
public class ConnectionDialog extends Dialog {

    private static final String SQLITE = "SQLite";
    private static final String MYSQL = "MySQL";
    private static final String POSTGRES = "PostgreSQL";
    private static final String GENERIC = "Generic JDBC";

    private ConnectionProfile result;

    private Combo typeCombo;
    private Text nameText;
    private Text urlText;
    private Text userText;
    private Text passwordText;
    private Text driverText;

    private static final String PASSWORD_AUTH = "Password";
    private static final String KEY_AUTH = "Private key";

    private Button tunnelCheck;
    private Composite tunnelBox;
    private Text sshHostText;
    private Text sshPortText;
    private Text sshUserText;
    private Combo authCombo;
    private Text sshPasswordText;
    private Text sshKeyText;
    private Text sshKeyPassphraseText;
    private Text knownHostsText;

    public ConnectionDialog(Shell parent) {
        super(parent);
        setText("Connect to Database");
    }

    /** Returns the chosen profile, or {@code null} if the dialog was cancelled. */
    public ConnectionProfile open() {
        Shell shell = new Shell(getParent(), SWT.DIALOG_TRIM | SWT.APPLICATION_MODAL);
        shell.setText(getText());
        shell.setLayout(new GridLayout(2, false));

        new Label(shell, SWT.NONE).setText("Database:");
        typeCombo = new Combo(shell, SWT.READ_ONLY);
        typeCombo.setItems(SQLITE, MYSQL, POSTGRES, GENERIC);
        typeCombo.setLayoutData(fill());

        new Label(shell, SWT.NONE).setText("Connection name:");
        nameText = new Text(shell, SWT.BORDER);
        nameText.setLayoutData(fill());

        new Label(shell, SWT.NONE).setText("JDBC URL:");
        Composite urlRow = new Composite(shell, SWT.NONE);
        urlRow.setLayout(new GridLayout(2, false));
        urlRow.setLayoutData(fill());
        urlText = new Text(urlRow, SWT.BORDER);
        urlText.setLayoutData(fill());
        Button browseButton = new Button(urlRow, SWT.PUSH);
        browseButton.setText("File...");
        browseButton.addListener(SWT.Selection, e -> chooseSqliteFile(shell));

        new Label(shell, SWT.NONE).setText("User:");
        userText = new Text(shell, SWT.BORDER);
        userText.setLayoutData(fill());

        new Label(shell, SWT.NONE).setText("Password:");
        passwordText = new Text(shell, SWT.PASSWORD | SWT.BORDER);
        passwordText.setLayoutData(fill());

        new Label(shell, SWT.NONE).setText("Driver class:");
        driverText = new Text(shell, SWT.BORDER);
        driverText.setLayoutData(fill());

        Label hint = new Label(shell, SWT.WRAP);
        hint.setText("Leave the driver class empty to use any JDBC driver found on the classpath.");
        GridData hintData = new GridData(SWT.FILL, SWT.CENTER, true, false);
        hintData.horizontalSpan = 2;
        hint.setLayoutData(hintData);

        tunnelCheck = new Button(shell, SWT.CHECK);
        tunnelCheck.setText("Connect through SSH tunnel");
        GridData tunnelCheckData = new GridData(SWT.FILL, SWT.CENTER, true, false);
        tunnelCheckData.horizontalSpan = 2;
        tunnelCheck.setLayoutData(tunnelCheckData);

        tunnelBox = new Composite(shell, SWT.NONE);
        tunnelBox.setLayout(new GridLayout(2, false));
        GridData tunnelBoxData = new GridData(SWT.FILL, SWT.CENTER, true, false);
        tunnelBoxData.horizontalSpan = 2;
        tunnelBox.setLayoutData(tunnelBoxData);

        sshHostText = labeledText(tunnelBox, "SSH host:", SWT.BORDER);
        sshPortText = labeledText(tunnelBox, "SSH port:", SWT.BORDER);
        sshPortText.setText("22");
        sshUserText = labeledText(tunnelBox, "SSH user:", SWT.BORDER);

        new Label(tunnelBox, SWT.NONE).setText("Authentication:");
        authCombo = new Combo(tunnelBox, SWT.READ_ONLY);
        authCombo.setItems(PASSWORD_AUTH, KEY_AUTH);
        authCombo.setLayoutData(fill());

        sshPasswordText = labeledText(tunnelBox, "SSH password:", SWT.PASSWORD | SWT.BORDER);
        sshKeyText = labeledFileText(tunnelBox, "Private key:", "Choose SSH private key");
        sshKeyPassphraseText = labeledText(tunnelBox, "Key passphrase:", SWT.PASSWORD | SWT.BORDER);
        knownHostsText = labeledFileText(tunnelBox, "Known hosts:", "Choose known_hosts file");
        knownHostsText.setText("~/.ssh/known_hosts");

        tunnelCheck.addListener(SWT.Selection, e -> refreshTunnelUi(shell));
        authCombo.addListener(SWT.Selection, e -> refreshTunnelUi(shell));
        authCombo.select(0);
        updateTunnelFields();

        Button testButton = new Button(shell, SWT.PUSH);
        testButton.setText("Test Connection");
        GridData testData = new GridData(SWT.CENTER, SWT.CENTER, true, false);
        testData.horizontalSpan = 2;
        testButton.setLayoutData(testData);
        testButton.addListener(SWT.Selection, e -> testConnection(shell));

        Composite buttons = new Composite(shell, SWT.NONE);
        buttons.setLayout(new GridLayout(2, true));
        GridData buttonsData = new GridData(SWT.FILL, SWT.CENTER, true, false);
        buttonsData.horizontalSpan = 2;
        buttons.setLayoutData(buttonsData);

        Button okButton = new Button(buttons, SWT.PUSH);
        okButton.setText("Connect");
        okButton.setLayoutData(fill());
        okButton.addListener(SWT.Selection, e -> {
            if (!validate(shell)) {
                return;
            }
            result = buildProfile();
            shell.close();
        });

        Button cancelButton = new Button(buttons, SWT.PUSH);
        cancelButton.setText("Cancel");
        cancelButton.setLayoutData(fill());
        cancelButton.addListener(SWT.Selection, e -> shell.close());
        shell.setDefaultButton(okButton);

        typeCombo.addListener(SWT.Selection, e -> applyDefaults(typeCombo.getText()));
        applyDefaults(SQLITE);

        // Compute both states once, then start collapsed. Toggling recomputes the size in
        // refreshTunnelUi(), so the dialog really grows/shrinks without clipping its buttons.
        tunnelCheck.setSelection(true);
        updateTunnelFields();
        shell.layout(true, true);
        Point expandedSize = shell.computeSize(SWT.DEFAULT, SWT.DEFAULT, true);

        tunnelCheck.setSelection(false);
        updateTunnelFields();
        shell.layout(true, true);
        Point collapsedSize = shell.computeSize(SWT.DEFAULT, SWT.DEFAULT, true);
        shell.setSize(Math.max(620, Math.max(expandedSize.x, collapsedSize.x)), collapsedSize.y);
        center(shell, getParent());
        shell.open();

        Display display = shell.getDisplay();
        while (!shell.isDisposed()) {
            if (!display.readAndDispatch()) {
                display.sleep();
            }
        }
        return result;
    }

    private void applyDefaults(String type) {
        switch (type) {
            case MYSQL -> {
                nameText.setText(MYSQL);
                urlText.setText("jdbc:mysql://localhost:3306/");
                driverText.setText("com.mysql.cj.jdbc.Driver");
            }
            case POSTGRES -> {
                nameText.setText(POSTGRES);
                urlText.setText("jdbc:postgresql://localhost:5432/");
                driverText.setText("org.postgresql.Driver");
            }
            case GENERIC -> {
                nameText.setText("JDBC");
                urlText.setText("");
                driverText.setText("");
            }
            default -> {
                nameText.setText(SQLITE);
                urlText.setText("jdbc:sqlite:");
                driverText.setText("org.sqlite.JDBC");
            }
        }
    }

    private void chooseSqliteFile(Shell shell) {
        if (!SQLITE.equals(typeCombo.getText())) {
            showMessage(shell, "Select SQLite as the database type first.", SWT.ICON_INFORMATION);
            return;
        }
        FileDialog dialog = new FileDialog(shell, SWT.OPEN);
        dialog.setText("Choose SQLite database file");
        dialog.setFilterExtensions(new String[] {"*.db;*.sqlite;*.sqlite3", "*.*"});
        String path = dialog.open();
        if (path != null) {
            urlText.setText("jdbc:sqlite:" + path);
        }
    }

    private void testConnection(Shell shell) {
        if (!validate(shell)) {
            return;
        }
        try {
            DatabaseService.testConnection(buildProfile());
            showMessage(shell, "Connection successful.", SWT.ICON_INFORMATION);
        } catch (SQLException e) {
            showMessage(shell, "Connection failed:\n" + messageOf(e), SWT.ICON_ERROR);
        }
    }

    private ConnectionProfile buildProfile() {
        SshTunnel ssh = SshTunnel.DISABLED;
        if (tunnelCheck.getSelection()) {
            boolean keyAuth = KEY_AUTH.equals(authCombo.getText());
            ssh = new SshTunnel(
                    true,
                    sshHostText.getText().trim(),
                    sshPortText.getText().trim(),
                    sshUserText.getText().trim(),
                    keyAuth ? "" : sshPasswordText.getText(),
                    keyAuth ? sshKeyText.getText().trim() : "",
                    keyAuth ? sshKeyPassphraseText.getText() : "",
                    knownHostsText.getText().trim());
        }
        return new ConnectionProfile(
                nameText.getText().trim(),
                typeCombo.getText(),
                urlText.getText().trim(),
                userText.getText().trim(),
                passwordText.getText(),
                driverText.getText().trim(),
                ssh);
    }

    private boolean validate(Shell shell) {
        if (urlText.getText().isBlank()) {
            showMessage(shell, "JDBC URL is required.", SWT.ICON_WARNING);
            return false;
        }
        if (!tunnelCheck.getSelection()) {
            return true;
        }
        if (SQLITE.equals(typeCombo.getText())) {
            showMessage(shell, "An SSH tunnel cannot be used with a local SQLite file.", SWT.ICON_WARNING);
            return false;
        }
        if (sshHostText.getText().isBlank() || sshUserText.getText().isBlank()) {
            showMessage(shell, "SSH host and user are required for the tunnel.", SWT.ICON_WARNING);
            return false;
        }
        if (!validPort(sshPortText.getText())) {
            showMessage(shell, "SSH port must be a number between 1 and 65535.", SWT.ICON_WARNING);
            return false;
        }
        if (KEY_AUTH.equals(authCombo.getText())) {
            if (sshKeyText.getText().isBlank()) {
                showMessage(shell, "Choose a private key file for SSH authentication.", SWT.ICON_WARNING);
                return false;
            }
        } else if (sshPasswordText.getText().isEmpty()) {
            showMessage(shell, "Enter an SSH password for authentication.", SWT.ICON_WARNING);
            return false;
        }
        return true;
    }

    private void updateTunnelFields() {
        boolean enabled = tunnelCheck.getSelection();
        GridData data = (GridData) tunnelBox.getLayoutData();
        data.exclude = !enabled;
        tunnelBox.setVisible(enabled);
        setEnabledRecursively(tunnelBox, enabled);
        if (!enabled) {
            return;
        }
        boolean keyAuth = KEY_AUTH.equals(authCombo.getText());
        sshPasswordText.setEnabled(!keyAuth);
        for (Control control : sshKeyText.getParent().getChildren()) {
            control.setEnabled(keyAuth);
        }
        sshKeyPassphraseText.setEnabled(keyAuth);
    }

    /** Applies visibility and resizes the shell in one repaint. */
    private void refreshTunnelUi(Shell shell) {
        shell.setRedraw(false);
        try {
            updateTunnelFields();
            shell.pack();
            Point size = shell.getSize();
            shell.setSize(Math.max(620, size.x), size.y);
        } finally {
            shell.setRedraw(true);
        }
    }

    private static void setEnabledRecursively(Control control, boolean enabled) {
        control.setEnabled(enabled);
        if (control instanceof Composite composite) {
            for (Control child : composite.getChildren()) {
                setEnabledRecursively(child, enabled);
            }
        }
    }

    private Text labeledText(Composite parent, String label, int style) {
        new Label(parent, SWT.NONE).setText(label);
        Text text = new Text(parent, style);
        text.setLayoutData(fill());
        return text;
    }

    private Text labeledFileText(Composite parent, String label, String dialogTitle) {
        new Label(parent, SWT.NONE).setText(label);
        Composite row = new Composite(parent, SWT.NONE);
        row.setLayout(new GridLayout(2, false));
        row.setLayoutData(fill());
        Text text = new Text(row, SWT.BORDER);
        text.setLayoutData(fill());
        Button browse = new Button(row, SWT.PUSH);
        browse.setText("Browse...");
        browse.addListener(SWT.Selection, e -> {
            FileDialog dialog = new FileDialog(parent.getShell(), SWT.OPEN);
            dialog.setText(dialogTitle);
            String path = dialog.open();
            if (path != null) {
                text.setText(path);
            }
        });
        return text;
    }

    private static boolean validPort(String value) {
        try {
            int port = Integer.parseInt(value.trim());
            return port > 0 && port <= 65535;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private static String messageOf(SQLException e) {
        return e.getMessage() == null ? e.toString() : e.getMessage();
    }

    private static void showMessage(Shell shell, String message, int icon) {
        MessageBox box = new MessageBox(shell, icon | SWT.OK);
        box.setText("DB Viewer");
        box.setMessage(message);
        box.open();
    }

    private static GridData fill() {
        return new GridData(SWT.FILL, SWT.CENTER, true, false);
    }

    private static void center(Shell shell, Shell parent) {
        Rectangle parentBounds = parent.getBounds();
        Rectangle shellBounds = shell.getBounds();
        int x = parentBounds.x + Math.max(0, (parentBounds.width - shellBounds.width) / 2);
        int y = parentBounds.y + Math.max(0, (parentBounds.height - shellBounds.height) / 2);
        shell.setLocation(x, y);
    }
}
