package dbviewer;

import java.util.List;

import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;

/** Lets the user pick which table an unconstrained foreign-key column refers to. */
final class ForeignKeyChoiceDialog {

    private final Shell parent;
    private ForeignKeyReference choice;
    private boolean remember;

    ForeignKeyChoiceDialog(Shell parent) {
        this.parent = parent;
    }

    /** Opens the dialog, returning the selected reference or null when cancelled. */
    ForeignKeyReference open(String column, String dbType, List<ForeignKeyReference> options,
            boolean canRemember) {
        Shell shell = new Shell(parent, SWT.DIALOG_TRIM | SWT.APPLICATION_MODAL | SWT.RESIZE);
        shell.setText("Open Referenced Rows");
        shell.setLayout(new GridLayout(1, false));

        Label heading = new Label(shell, SWT.NONE);
        heading.setText("Which table does " + column + " refer to?");
        heading.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        org.eclipse.swt.widgets.List list = new org.eclipse.swt.widgets.List(shell,
                SWT.BORDER | SWT.SINGLE | SWT.V_SCROLL | SWT.H_SCROLL);
        for (ForeignKeyReference option : options) {
            list.add(option.label(dbType));
        }
        if (!options.isEmpty()) {
            list.select(0);
        }
        GridData listData = new GridData(SWT.FILL, SWT.FILL, true, true);
        listData.heightHint = 140;
        list.setLayoutData(listData);

        final Button rememberButton;
        if (canRemember) {
            rememberButton = new Button(shell, SWT.CHECK);
            rememberButton.setText("Remember this table for the session");
            rememberButton.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        } else {
            rememberButton = null;
        }
        list.addListener(SWT.DefaultSelection, e -> {
            int index = list.getSelectionIndex();
            if (index >= 0 && index < options.size()) {
                choice = options.get(index);
                remember = rememberButton != null && rememberButton.getSelection();
                shell.close();
            }
        });

        Composite buttons = new Composite(shell, SWT.NONE);
        buttons.setLayout(new GridLayout(2, true));
        buttons.setLayoutData(new GridData(SWT.END, SWT.CENTER, true, false));

        Button ok = new Button(buttons, SWT.PUSH);
        ok.setText("Open Rows");
        ok.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, false, false));
        ok.addListener(SWT.Selection, e -> {
            int index = list.getSelectionIndex();
            if (index >= 0 && index < options.size()) {
                choice = options.get(index);
            }
            remember = rememberButton != null && rememberButton.getSelection();
            shell.close();
        });

        Button cancel = new Button(buttons, SWT.PUSH);
        cancel.setText("Cancel");
        cancel.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, false, false));
        cancel.addListener(SWT.Selection, e -> shell.close());

        shell.setDefaultButton(ok);
        shell.addListener(SWT.Traverse, e -> {
            if (e.detail == SWT.TRAVERSE_ESCAPE) {
                shell.close();
                e.doit = false;
            }
        });
        shell.setSize(560, 280);
        centerOverParent(shell);
        shell.open();

        Display display = parent.getDisplay();
        while (!shell.isDisposed()) {
            if (!display.readAndDispatch()) {
                display.sleep();
            }
        }
        return choice;
    }

    /** True when the user asked to keep the chosen table for this connection. */
    boolean remember() {
        return remember;
    }

    private void centerOverParent(Shell shell) {
        int x = parent.getLocation().x + (parent.getSize().x - shell.getSize().x) / 2;
        int y = parent.getLocation().y + (parent.getSize().y - shell.getSize().y) / 2;
        shell.setLocation(Math.max(0, x), Math.max(0, y));
    }
}
