package dbviewer;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.CTabFolder;
import org.eclipse.swt.custom.CTabItem;
import org.eclipse.swt.custom.SashForm;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;

/**
 * The main tabbed area. Tabs can be dragged by their tab strip to reorder them, moved between
 * folders, or dropped on a folder body to split the main panel horizontally or vertically.
 */
final class TabWorkspace implements TabDrag.Host {

    private final DbViewer owner;
    private final SashForm sash;
    private final TabDrag drag;
    private final CTabFolder primary;
    private CTabFolder secondary;
    private final List<QueryTab> tabs = new ArrayList<>();

    /** The folder that has focus, or the one used last. */
    private CTabFolder current;
    private int nextId = 1;
    private int nextTabNumber = 1;

    TabWorkspace(DbViewer owner, Composite parent) {
        this.owner = owner;
        drag = new TabDrag(owner.shell(), this);
        sash = new SashForm(parent, SWT.HORIZONTAL);
        sash.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        primary = createFolder();
        current = primary;
    }

    private CTabFolder createFolder() {
        CTabFolder folder = new CTabFolder(sash, SWT.TOP | SWT.BORDER);
        folder.setTabHeight(folder.getTabHeight() + 3);
        folder.setUnselectedCloseVisible(true);

        Button newTabButton = new Button(folder, SWT.PUSH);
        newTabButton.setText("+");
        newTabButton.setToolTipText("New query tab (Ctrl+T)");
        newTabButton.addListener(SWT.Selection, e -> {
            current = folder;
            newTab();
        });
        folder.setTopRight(newTabButton);

        folder.addListener(SWT.Close, e -> {
            e.doit = false; // closeTab disposes the item itself
            if (e.item instanceof CTabItem item && item.getData("tab") instanceof QueryTab tab) {
                closeTab(tab);
            }
        });
        folder.addListener(SWT.Selection, e -> {
            if (!folder.isDisposed()) {
                current = folder;
                owner.updateConnectionUi();
            }
        });
        drag.attach(folder);
        return folder;
    }

    @Override
    public List<CTabFolder> folders() {
        List<CTabFolder> result = new ArrayList<>();
        result.add(primary);
        if (secondary != null && !secondary.isDisposed()) {
            result.add(secondary);
        }
        return result;
    }

    @Override
    public boolean splits() {
        return true;
    }

    /** Handles a completed drag, either inserting the tab or splitting the panel first. */
    @Override
    public void drop(CTabItem item, CTabFolder target, int zone, int index) {
        QueryTab tab = (QueryTab) item.getData("tab");
        if (tab == null) {
            return;
        }
        CTabFolder from = item.getParent();
        if (zone == TabDrag.INSERT) {
            tab.moveTo(target, index);
        } else {
            ensureSecondary();
            sash.setOrientation(zone == TabDrag.LEFT || zone == TabDrag.RIGHT
                    ? SWT.HORIZONTAL : SWT.VERTICAL);
            tab.moveTo(secondary, secondary.getItemCount());
        }
        current = tab.item().getParent();
        collapseIfEmpty(from);
        sash.layout(true, true);
        tab.focusEditor();
        owner.updateConnectionUi();
    }

    private void ensureSecondary() {
        if (secondary == null || secondary.isDisposed()) {
            secondary = createFolder();
            sash.setWeights(new int[] {60, 40});
            sash.layout(true, true);
        }
    }

    private void collapseIfEmpty(CTabFolder folder) {
        if (folder == secondary && folder.getItemCount() == 0) {
            secondary.dispose();
            secondary = null;
            if (current == folder) {
                current = primary;
            }
            sash.setOrientation(SWT.HORIZONTAL);
        }
        if (current == primary && primary.getItemCount() == 0
                && secondary != null && secondary.getItemCount() > 0) {
            current = secondary;
            if (secondary.getSelection() == null) {
                secondary.setSelection(0);
            }
        }
    }

    /** Creates a new tab in the folder that currently has focus. */
    void newTab() {
        CTabFolder folder = currentFolder();
        QueryTab tab = new QueryTab(owner, nextId++, "Query " + nextTabNumber++, folder);
        tabs.add(tab);
        folder.setSelection(tab.item());
        current = folder;
        tab.focusEditor();
        owner.updateConnectionUi();
    }

    void closeActiveTab() {
        QueryTab tab = activeTab();
        if (tab != null) {
            closeTab(tab);
        }
    }

    private void closeTab(QueryTab tab) {
        if (tabs.size() <= 1) {
            return;
        }
        CTabFolder folder = tab.item().getParent();
        tabs.remove(tab);
        tab.dispose();
        collapseIfEmpty(folder);
        if (current == null || current.isDisposed()) {
            current = folders().get(0);
        }
        owner.updateConnectionUi();
    }

    private CTabFolder currentFolder() {
        List<CTabFolder> all = folders();
        for (Control c = owner.shell().getDisplay().getFocusControl(); c != null; c = c.getParent()) {
            if (c instanceof CTabFolder folder && all.contains(folder)) {
                current = folder;
                break;
            }
        }
        if (current == null || current.isDisposed() || !all.contains(current)) {
            current = all.get(0);
        }
        return current;
    }

    QueryTab activeTab() {
        CTabItem selected = currentFolder().getSelection();
        return selected == null ? null : (QueryTab) selected.getData("tab");
    }

    List<QueryTab> allTabs() {
        return List.copyOf(tabs);
    }

    int tabCount() {
        return tabs.size();
    }
}
