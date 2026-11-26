package dbviewer;

import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Font;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Text;

/** Shows the pending row changes and asks for confirmation before writing them to the database. */
final class SaveChangesDialog {

    private final Shell parent;
    private final Font monoFont;
    private boolean saved;

    SaveChangesDialog(Shell parent, Font monoFont) {
        this.parent = parent;
        this.monoFont = monoFont;
    }

    /** Opens the dialog and returns true when the user confirms the save. */
    boolean open(String summary) {
        Shell shell = new Shell(parent, SWT.DIALOG_TRIM | SWT.APPLICATION_MODAL | SWT.RESIZE);
        shell.setText("Save Changes");
        shell.setLayout(new GridLayout(1, false));

        Label heading = new Label(shell, SWT.NONE);
        heading.setText("Review the changes that will be written to the database:");
        heading.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        Text details = new Text(shell,
                SWT.BORDER | SWT.MULTI | SWT.V_SCROLL | SWT.H_SCROLL | SWT.READ_ONLY);
        if (monoFont != null) {
            details.setFont(monoFont);
        }
        details.setText(summary);
        details.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));

        Composite buttons = new Composite(shell, SWT.NONE);
        buttons.setLayout(new GridLayout(2, true));
        buttons.setLayoutData(new GridData(SWT.END, SWT.CENTER, true, false));

        Button save = new Button(buttons, SWT.PUSH);
        save.setText("Save to Database");
        save.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, false, false));
        save.addListener(SWT.Selection, e -> {
            saved = true;
            shell.close();
        });

        Button cancel = new Button(buttons, SWT.PUSH);
        cancel.setText("Cancel");
        cancel.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, false, false));
        cancel.addListener(SWT.Selection, e -> shell.close());

        shell.setDefaultButton(save);
        shell.addListener(SWT.Traverse, e -> {
            if (e.detail == SWT.TRAVERSE_ESCAPE) {
                shell.close();
                e.doit = false;
            }
        });
        shell.setSize(680, 480);
        centerOverParent(shell);
        shell.open();

        Display display = parent.getDisplay();
        while (!shell.isDisposed()) {
            if (!display.readAndDispatch()) {
                display.sleep();
            }
        }
        return saved;
    }

    private void centerOverParent(Shell shell) {
        int x = parent.getLocation().x + (parent.getSize().x - shell.getSize().x) / 2;
        int y = parent.getLocation().y + (parent.getSize().y - shell.getSize().y) / 2;
        shell.setLocation(Math.max(0, x), Math.max(0, y));
    }
}
