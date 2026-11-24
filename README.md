# dbviewer

A small desktop database viewer built with **Java 21**, **SWT**, and **JDBC**.

## Features

- Menu bar: connect/disconnect, new/close query tab, run queries, refresh tables, exit, about.
- **Left pane** — lists tables and views in a tree grouped by catalog or schema (whichever the
  database exposes), with a filter box. Groups start collapsed; double-click a group or table to
  expand or collapse it. Expanding a table loads its columns as child items, including data types
  and primary-key markers. The **Select All** context menu item builds and immediately runs a
  `SELECT *` query for the chosen table(s).
- **Main pane** — multiple query tabs (Ctrl+T to add, Ctrl+W to close), each with a monospace
  SQL editor featuring syntax highlighting and indentation-preserving Enter/Tab.
  Drag a tab by its tab strip: an insertion line shows where it will land, while dropping on
  a folder body near an edge splits the main panel horizontally or vertically. Tabs can also
  be dragged between panes; the editor text, cursor position and results follow the tab.
  **Ctrl+Enter** runs the statement under the cursor; **Ctrl+Shift+Enter** (or F5 / *Run Query*)
  runs every statement in the editor.
- **Bottom pane** — a virtualized results table in each tab, with column headers that sort the
  rows ascending/descending using natural ordering (`item2` before `item10`), and a context menu
  to copy a field, copy rows (tab-separated), or delete rows from the database after a confirmation.
  Shows row count and execution time; update statements show the affected-row count.
- JDBC drivers bundled for **SQLite**, **MySQL**, and **PostgreSQL**. Any other JDBC driver
  can be used by choosing *Generic JDBC* and adding its jar to the classpath/dependencies.
- Optional **SSH tunnel**: enable it in the connection dialog to reach a remote database through
  an SSH jump host, using either a password or a private key. Host keys are verified against
  `known_hosts` (default `~/.ssh/known_hosts`).
- Multiple semicolon-separated statements can be run at once (quotes and comments are
  handled correctly); the last result set is displayed.

## Build and run

```bash
cd ~/multipass/dbviewer
mvn clean package
java -jar target/dbviewer.jar
```

Or run directly from Maven during development:

```bash
mvn exec:java
```

The Maven profiles pick the correct SWT native fragment for Linux x86_64, Windows x86_64,
macOS x86_64, and macOS aarch64. If you are on another architecture, change the
`swt-*` profile in `pom.xml` to the matching `org.eclipse.swt.*` artifact.

## Using it

1. Choose **File → Connect…** (Ctrl+O).
2. Pick a database type, enter/adjust the JDBC URL, user and password, then click **Connect**.
   - SQLite: click **File…** to pick a `.db` file, giving a URL such as
     `jdbc:sqlite:/path/to/mydb.db`.
   - MySQL: e.g. `jdbc:mysql://localhost:3306/mydb?serverTimezone=UTC`.
   - PostgreSQL: e.g. `jdbc:postgresql://localhost:5432/mydb`.
   - **Test Connection** checks the URL and credentials before you connect.
   - To connect through SSH, tick **Connect through SSH tunnel** and fill in the SSH host, port,
     user, and either a password or a private key file. The JDBC URL should still point at the
     remote database host; it is transparently rewritten to a local forwarded port. Add the SSH
     host key to `~/.ssh/known_hosts` first (for example, `ssh-keyscan host >> ~/.ssh/known_hosts`).
3. Tables appear in the left pane, grouped by schema or catalog with every group initially
   collapsed. Double-click a group or table to expand/collapse it; expanding a table shows its
   columns. Select one or more tables and use **Select All** from the context menu to run queries
   showing all their rows.
4. Type or edit SQL in the active tab: **Ctrl+Enter** runs the statement at the cursor,
   **Ctrl+Shift+Enter** runs the whole script. Results appear below the editor in that tab.
   Use **Ctrl+T** for another query tab, drag tabs by their tab strips to reorder or split
   the main panel, and right-click the results for copy/delete actions.

## Notes

- Results are capped at 10,000 rows to keep the UI responsive; the label says when a
  result was truncated. Cell values with line breaks are escaped, long values are shortened,
  and binary values are shown as `[BINARY]`.
- **Delete Row(s)** asks for confirmation, then issues a `DELETE` for the selected rows. Tables
  with a primary key are matched by that key; otherwise all columns in the result are used. If a
  query omits the primary key column, deletion is refused.
- Status bar messages are shortened to fit the window; hover over them for the full text.
- Queries run on the UI thread with a wait cursor. Long-running statements will block
  the window until they finish.
- The Eclipse project files (`.project`, `.classpath`) are configured for m2e, so the
  project can also be imported into Eclipse as a Maven project.
