package dbviewer;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.SashForm;
import org.eclipse.swt.graphics.Font;
import org.eclipse.swt.graphics.FontData;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.MenuItem;
import org.eclipse.swt.widgets.MessageBox;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Text;
import org.eclipse.swt.widgets.Tree;
import org.eclipse.swt.widgets.TreeItem;

/** Main application window: menu bar, schema table tree, query tabs and status bar. */
public final class DbViewer {

    private final Display display;
    private final Shell shell;
    private final DatabaseService database = new DatabaseService();
    private final TabWorkspace tabs;

    private Tree tableTree;
    private Text tableFilter;
    private Button runButton;
    private Label connectionLabel;
    private Label statusLabel;
    private Composite statusBar;

    private MenuItem disconnectMenuItem;
    private MenuItem closeTabMenuItem;
    private MenuItem runMenuItem;
    private MenuItem refreshMenuItem;

    private Font monoFont;
    private List<TableInfo> allTables = List.of();
    private List<TableInfo> visibleTables = List.of();
    private String connectionText = "Not connected";
    private String statusText = "";

    public DbViewer(Display display) {
        this.display = display;
        this.shell = new Shell(display);
        shell.setText("DB Viewer");
        shell.setSize(1150, 760);
        shell.setLayout(new GridLayout(1, false));

        FontData fontData = display.getSystemFont().getFontData()[0];
        fontData.setName("Monospace");
        fontData.setStyle(SWT.NORMAL);
        monoFont = new Font(display, fontData);

        shell.addListener(SWT.Dispose, e -> {
            database.disconnect();
            if (monoFont != null && !monoFont.isDisposed()) {
                monoFont.dispose();
            }
        });

        createMenuBar();

        SashForm outer = new SashForm(shell, SWT.HORIZONTAL);
        outer.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        createLeftPane(outer);

        Composite workPane = new Composite(outer, SWT.NONE);
        workPane.setLayout(new GridLayout(1, false));
        createQueryToolbar(workPane);
        tabs = new TabWorkspace(this, workPane);
        tabs.newTab();
        outer.setWeights(new int[] {22, 78});

        createStatusBar();
    }

    public static void main(String[] args) {
        Display display = new Display();
        try {
            new DbViewer(display).run();
        } finally {
            display.dispose();
        }
    }

    /** Opens the window and runs the SWT event loop. */
    public void run() {
        updateConnectionUi();
        setStatus("Ready. Connect to a database to begin.", false);
        shell.open();
        while (!shell.isDisposed()) {
            if (!display.readAndDispatch()) {
                display.sleep();
            }
        }
    }

    private void createMenuBar() {
        Menu bar = new Menu(shell, SWT.BAR);
        shell.setMenuBar(bar);

        Menu fileMenu = new Menu(shell, SWT.DROP_DOWN);
        MenuItem fileHeader = new MenuItem(bar, SWT.CASCADE);
        fileHeader.setText("File");
        fileHeader.setMenu(fileMenu);
        menuAction(fileMenu, "Connect...\tCtrl+O", SWT.CONTROL | 'O', this::connect);
        disconnectMenuItem = menuAction(fileMenu, "Disconnect", 0, this::disconnect);
        new MenuItem(fileMenu, SWT.SEPARATOR);
        menuAction(fileMenu, "Exit\tCtrl+Q", SWT.CONTROL | 'Q', shell::close);

        Menu queryMenu = new Menu(shell, SWT.DROP_DOWN);
        MenuItem queryHeader = new MenuItem(bar, SWT.CASCADE);
        queryHeader.setText("Query");
        queryHeader.setMenu(queryMenu);
        menuAction(queryMenu, "New Tab\tCtrl+T", SWT.CONTROL | 'T', this::newTab);
        closeTabMenuItem = menuAction(queryMenu, "Close Tab\tCtrl+W", SWT.CONTROL | 'W', this::closeActiveTab);
        new MenuItem(queryMenu, SWT.SEPARATOR);
        runMenuItem = menuAction(queryMenu, "Run Query\tF5", SWT.F5, this::runQuery);
        refreshMenuItem = menuAction(queryMenu, "Refresh Tables\tCtrl+R", SWT.CONTROL | 'R', this::refreshTables);
        menuAction(queryMenu, "Clear Results", 0, this::clearResultsUi);
        menuAction(queryMenu, "Clear Editor", 0, this::clearEditor);

        Menu helpMenu = new Menu(shell, SWT.DROP_DOWN);
        MenuItem helpHeader = new MenuItem(bar, SWT.CASCADE);
        helpHeader.setText("Help");
        helpHeader.setMenu(helpMenu);
        menuAction(helpMenu, "About", 0, this::about);
    }

    private MenuItem menuAction(Menu menu, String text, int accelerator, Runnable action) {
        MenuItem item = new MenuItem(menu, SWT.PUSH);
        item.setText(text);
        if (accelerator != 0) {
            item.setAccelerator(accelerator);
        }
        item.addListener(SWT.Selection, e -> action.run());
        return item;
    }

    private void createLeftPane(Composite parent) {
        Composite pane = new Composite(parent, SWT.NONE);
        pane.setLayout(new GridLayout(1, false));

        Label header = new Label(pane, SWT.NONE);
        header.setText("Tables by schema");
        header.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, false, false));

        Composite filterRow = new Composite(pane, SWT.NONE);
        filterRow.setLayout(new GridLayout(2, false));
        filterRow.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        tableFilter = new Text(filterRow, SWT.BORDER | SWT.SEARCH);
        tableFilter.setMessage("Filter tables...");
        tableFilter.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        tableFilter.addListener(SWT.Modify, e -> populateTableList());

        Button refreshButton = new Button(filterRow, SWT.PUSH);
        refreshButton.setText("Refresh");
        refreshButton.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, false, false));
        refreshButton.addListener(SWT.Selection, e -> loadTables());

        tableTree = new Tree(pane, SWT.BORDER | SWT.MULTI | SWT.V_SCROLL | SWT.H_SCROLL);
        tableTree.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        tableTree.addListener(SWT.DefaultSelection, e -> {
            if (e.item instanceof TreeItem item && item.getItemCount() > 0) {
                boolean expand = !item.getExpanded();
                if (expand && item.getData("table") instanceof TableInfo table
                        && !Boolean.TRUE.equals(item.getData("columnsLoaded"))) {
                    loadColumns(item, table);
                }
                item.setExpanded(expand);
            }
        });
        tableTree.addListener(SWT.Expand, e -> {
            if (e.item instanceof TreeItem item
                    && item.getData("table") instanceof TableInfo table
                    && !Boolean.TRUE.equals(item.getData("columnsLoaded"))) {
                loadColumns(item, table);
            }
        });
        createTableContextMenu();
    }

    private void createTableContextMenu() {
        Menu menu = new Menu(tableTree);
        tableTree.setMenu(menu);
        menuAction(menu, "Select All", 0, this::selectAll);
        new MenuItem(menu, SWT.SEPARATOR);
        menuAction(menu, "Refresh Tables", 0, this::refreshTables);
    }

    private void createQueryToolbar(Composite parent) {
        Composite toolbar = new Composite(parent, SWT.NONE);
        toolbar.setLayout(new GridLayout(4, false));
        toolbar.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        Label header = new Label(toolbar, SWT.NONE);
        header.setText("SQL Query");
        header.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        Button newTabButton = new Button(toolbar, SWT.PUSH);
        newTabButton.setText("New Tab");
        newTabButton.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, false, false));
        newTabButton.addListener(SWT.Selection, e -> newTab());

        runButton = new Button(toolbar, SWT.PUSH);
        runButton.setText("Run Query  (Ctrl+Enter)");
        runButton.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, false, false));
        runButton.addListener(SWT.Selection, e -> runQuery());

        Button clearButton = new Button(toolbar, SWT.PUSH);
        clearButton.setText("Clear");
        clearButton.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, false, false));
        clearButton.addListener(SWT.Selection, e -> clearEditor());
    }

    private void newTab() {
        tabs.newTab();
    }

    private void closeActiveTab() {
        tabs.closeActiveTab();
    }

    private QueryTab activeTab() {
        return tabs.activeTab();
    }

    private void createStatusBar() {
        statusBar = new Composite(shell, SWT.NONE);
        statusBar.setLayout(new GridLayout(2, false));
        statusBar.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        connectionLabel = new Label(statusBar, SWT.BORDER);
        connectionLabel.setLayoutData(statusCellData());
        statusLabel = new Label(statusBar, SWT.BORDER);
        statusLabel.setLayoutData(statusCellData());
        statusBar.addListener(SWT.Resize, e -> refreshStatusText());
    }

    /** A cell with no natural width: both labels share the bar and are ellipsized on resize. */
    private static GridData statusCellData() {
        GridData data = new GridData(SWT.FILL, SWT.CENTER, true, false);
        data.widthHint = 0;
        data.minimumWidth = 0;
        return data;
    }

    private void refreshStatusText() {
        if (statusLabel == null || statusLabel.isDisposed() || statusBar == null) {
            return;
        }
        int width = Math.max(60, statusBar.getSize().x / 2 - 10);
        GC gc = new GC(statusBar);
        try {
            gc.setFont(display.getSystemFont());
            connectionLabel.setText(ellipsize(gc, connectionText, width));
            statusLabel.setText(ellipsize(gc, statusText, width));
        } finally {
            gc.dispose();
        }
        connectionLabel.setToolTipText(connectionText);
        statusLabel.setToolTipText(statusText);
    }

    private static String ellipsize(GC gc, String text, int width) {
        if (text == null) {
            return "";
        }
        if (gc.textExtent(text).x <= width) {
            return text;
        }
        String shortened = text;
        while (shortened.length() > 1 && gc.textExtent(shortened + "…").x > width) {
            shortened = shortened.substring(0, shortened.length() - 1);
        }
        return shortened + "…";
    }

    private void connect() {
        ConnectionProfile profile = new ConnectionDialog(shell).open();
        if (profile == null) {
            return;
        }
        try {
            database.connect(profile);
            setStatus("Connected to " + profile.name() + ".", false);
            loadTables();
        } catch (SQLException e) {
            reportError("Connection failed", e);
            setStatus("Connection failed: " + messageOf(e), true);
        }
        updateConnectionUi();
    }

    private void disconnect() {
        database.disconnect();
        allTables = List.of();
        populateTableList();
        for (QueryTab tab : tabs.allTabs()) {
            tab.clearResults();
        }
        setStatus("Disconnected.", false);
        updateConnectionUi();
    }

    private void refreshTables() {
        loadTables();
    }

    private void loadTables() {
        if (!database.isConnected()) {
            allTables = List.of();
            populateTableList();
            return;
        }
        try {
            allTables = database.listTables();
            populateTableList();
            setStatus("Loaded " + allTables.size() + " table(s)/view(s).", false);
        } catch (SQLException e) {
            reportError("Could not load tables", e);
            setStatus("Could not load tables: " + messageOf(e), true);
        }
    }

    private void populateTableList() {
        String filter = tableFilter.getText().trim().toLowerCase();
        visibleTables = allTables.stream()
                .filter(t -> filter.isEmpty()
                        || t.name().toLowerCase().contains(filter)
                        || t.schema().toLowerCase().contains(filter))
                .toList();

        // Databases that expose schemas (PostgreSQL) group by schema; those that don't
        // (MySQL, SQLite) group by catalog instead.
        boolean groupBySchema = allTables.stream().anyMatch(t -> !t.schema().isBlank());
        Map<String, List<TableInfo>> grouped = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (TableInfo table : visibleTables) {
            grouped.computeIfAbsent(groupLabel(table, groupBySchema), key -> new ArrayList<>()).add(table);
        }

        tableTree.removeAll();
        for (Map.Entry<String, List<TableInfo>> entry : grouped.entrySet()) {
            TreeItem groupItem = new TreeItem(tableTree, SWT.NONE);
            groupItem.setText(entry.getKey() + "  (" + entry.getValue().size() + ")");
            for (TableInfo table : entry.getValue()) {
                TreeItem tableItem = new TreeItem(groupItem, SWT.NONE);
                String suffix = table.type().isEmpty() ? "" : "  [" + table.type() + "]";
                tableItem.setText(table.name() + suffix);
                tableItem.setData("table", table);
                tableItem.setData("columnsLoaded", Boolean.FALSE);
                new TreeItem(tableItem, SWT.NONE).setText("Loading columns...");
            }
            groupItem.setExpanded(false);
        }
    }

    /** Replaces the placeholder under a table item with its JDBC column metadata. */
    private void loadColumns(TreeItem item, TableInfo table) {
        boolean expanded = item.getExpanded();
        item.removeAll();
        try {
            List<ColumnInfo> columns = database.listColumns(table);
            if (columns.isEmpty()) {
                new TreeItem(item, SWT.NONE).setText("(no columns)");
            }
            for (ColumnInfo column : columns) {
                TreeItem columnItem = new TreeItem(item, SWT.NONE);
                columnItem.setText(column.displayLabel());
                columnItem.setData("column", column);
                columnItem.setData("table", table);
            }
        } catch (SQLException e) {
            new TreeItem(item, SWT.NONE).setText("(could not load columns)");
            reportError("Could not load columns for " + table.name(), e);
        }
        item.setData("columnsLoaded", Boolean.TRUE);
        item.setExpanded(expanded);
    }

    private static String groupLabel(TableInfo table, boolean groupBySchema) {
        String group = groupBySchema ? table.schema() : table.catalog();
        if (!group.isBlank()) {
            return group;
        }
        String fallback = groupBySchema ? table.catalog() : table.schema();
        return fallback.isBlank() ? "(default)" : fallback;
    }

    /** "Select All": run a SELECT that shows every row of each chosen table. */
    private void selectAll() {
        if (!database.isConnected()) {
            showInfo("Connect to a database first.");
            return;
        }
        List<TableInfo> targets = new ArrayList<>();
        for (TreeItem item : tableTree.getSelection()) {
            if (item.getData("table") instanceof TableInfo table) {
                targets.add(table);
            }
        }
        for (int i = 0; i < targets.size(); i++) {
            runTableQuery(targets.get(i), i > 0);
        }
    }

    private void runTableQuery(TableInfo table, boolean newTab) {
        if (newTab) {
            newTab();
        }
        QueryTab tab = activeTab();
        if (tab == null) {
            return;
        }
        String sql = "SELECT * FROM " + table.qualifiedName(database.profile().dbType());
        tab.insertQuery(sql);
        tab.runAll();
    }

    private void runQuery() {
        QueryTab tab = activeTab();
        if (tab != null) {
            tab.runAll();
        }
    }

    private void clearResultsUi() {
        QueryTab tab = activeTab();
        if (tab != null) {
            tab.clearResults();
        }
    }

    private void clearEditor() {
        QueryTab tab = activeTab();
        if (tab != null) {
            tab.clearEditor();
        }
    }

    void updateConnectionUi() {
        boolean connected = database.isConnected();
        connectionText = connected
                ? "Connected: " + database.profile().name() + " — " + database.profile().jdbcUrl()
                : "Not connected";
        refreshStatusText();

        boolean canRun = connected && activeTab() != null;
        disconnectMenuItem.setEnabled(connected);
        runMenuItem.setEnabled(canRun);
        refreshMenuItem.setEnabled(connected);
        runButton.setEnabled(canRun);
        closeTabMenuItem.setEnabled(tabs.tabCount() > 1);
    }

    void setStatus(String message, boolean error) {
        statusText = message;
        statusLabel.setForeground(display.getSystemColor(error ? SWT.COLOR_RED : SWT.COLOR_BLACK));
        refreshStatusText();
    }

    private void about() {
        showInfo("DB Viewer\n\nSWT + JDBC database viewer.\n"
                + "Collapsed catalog/schema table tree, draggable/splittable query tabs, "
                + "SQL highlighting, Ctrl+Enter for the current statement and result context menus.\n"
                + "Supports SQLite, MySQL, PostgreSQL and generic JDBC URLs.");
    }

    Display display() {
        return display;
    }

    Shell shell() {
        return shell;
    }

    DatabaseService database() {
        return database;
    }

    Font monoFont() {
        return monoFont;
    }

    void showInfo(String message) {
        MessageBox box = new MessageBox(shell, SWT.ICON_INFORMATION | SWT.OK);
        box.setText("DB Viewer");
        box.setMessage(message);
        box.open();
    }

    /** Shows a Yes/No confirmation dialog for destructive actions. */
    boolean confirm(String question, String detail) {
        return confirm("Confirm Delete", question, detail);
    }

    /** Shows a Yes/No confirmation dialog with a caller-supplied title. */
    boolean confirm(String title, String question, String detail) {
        MessageBox box = new MessageBox(shell, SWT.ICON_QUESTION | SWT.YES | SWT.NO);
        box.setText(title);
        box.setMessage(question + "\n\n" + detail);
        return box.open() == SWT.YES;
    }

    void reportError(String title, SQLException e) {
        e.printStackTrace();
        MessageBox box = new MessageBox(shell, SWT.ICON_ERROR | SWT.OK);
        box.setText(title);
        box.setMessage(messageOf(e));
        box.open();
    }

    static String messageOf(SQLException e) {
        return e.getMessage() == null ? e.toString() : e.getMessage();
    }
}
