package dev.jadxbinexport;

import java.awt.BorderLayout;
import java.awt.Dialog;
import java.awt.Dimension;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.SwingUtilities;
import javax.swing.WindowConstants;

/**
 * A small modeless progress dialog with a Cancel button, used to view export /
 * diff progress in jadx-gui (jadx's plugin API exposes no progress bar of its
 * own, only {@code getMainFrame()}, so we host our own).
 *
 * <p>Thread-safe by construction: the exporter drives {@link #stage}/{@link
 * #update} from its worker thread, and every Swing access is marshalled onto the
 * EDT via {@link SwingUtilities#invokeLater}. Those posts run in FIFO order, so
 * the widget-building post scheduled in the constructor always runs before any
 * update. {@link #cancelled()} reads a {@code volatile} flag the Cancel button
 * sets on the EDT, so it is safe to poll from the worker.
 */
final class ExportProgressDialog implements ExportProgress {

	private final JFrame parent;
	private final String title;

	private JDialog dialog;
	private JProgressBar bar;
	private JLabel note;
	private volatile boolean cancelled;

	ExportProgressDialog(JFrame parent, String title) {
		this.parent = parent;
		this.title = title;
		SwingUtilities.invokeLater(this::build);
	}

	private void build() {
		dialog = new JDialog(parent, title, false);
		bar = new JProgressBar(0, 100);
		bar.setStringPainted(true);
		note = new JLabel("Starting…");
		JButton cancel = new JButton("Cancel");
		Runnable requestCancel = () -> {
			cancelled = true;
			note.setText("Cancelling…");
			cancel.setEnabled(false);
		};
		cancel.addActionListener(e -> requestCancel.run());
		// Closing via the title-bar X is the natural "stop this" gesture, so make
		// it request cancel too (not the default HIDE_ON_CLOSE, which would hide
		// the dialog while the export kept running invisibly).
		dialog.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
		dialog.addWindowListener(new WindowAdapter() {
			@Override
			public void windowClosing(WindowEvent e) {
				requestCancel.run();
			}
		});

		JPanel content = new JPanel(new BorderLayout(8, 10));
		content.setBorder(BorderFactory.createEmptyBorder(12, 14, 12, 14));
		content.add(note, BorderLayout.NORTH);
		content.add(bar, BorderLayout.CENTER);
		JPanel south = new JPanel();
		south.add(cancel);
		content.add(south, BorderLayout.SOUTH);

		dialog.setContentPane(content);
		dialog.setPreferredSize(new Dimension(440, 150));
		dialog.pack();
		dialog.setLocationRelativeTo(parent);
		dialog.setVisible(true); // modeless: returns immediately, never blocks the EDT
	}

	@Override
	public void stage(String label, int total) {
		SwingUtilities.invokeLater(() -> {
			if (note == null) {
				return;
			}
			note.setText(label);
			if (total <= 0) {
				bar.setIndeterminate(true);
				bar.setString("");
			} else {
				bar.setIndeterminate(false);
				bar.setMaximum(total);
				bar.setValue(0);
				bar.setString("0 / " + total);
			}
		});
	}

	@Override
	public void update(int done, int total) {
		SwingUtilities.invokeLater(() -> {
			if (bar == null || bar.isIndeterminate()) {
				return;
			}
			bar.setMaximum(total);
			bar.setValue(done);
			bar.setString(done + " / " + total);
		});
	}

	@Override
	public boolean cancelled() {
		return cancelled;
	}

	/**
	 * Surfaces an advisory as a MODELESS warning dialog parented to the main
	 * frame. Modeless is load-bearing: a modal JOptionPane would block input to
	 * this (modeless) progress dialog, making its Cancel button unreachable
	 * while e.g. a long bindiff run proceeds behind the warning. Implemented
	 * here (not per-caller) so every flow that uses this dialog surfaces warns
	 * - the interface contract says a log-only warning is invisible in the GUI.
	 */
	@Override
	public void warn(String message) {
		SwingUtilities.invokeLater(() -> {
			JOptionPane pane = new JOptionPane(message, JOptionPane.WARNING_MESSAGE);
			JDialog d = pane.createDialog(parent, title);
			d.setModalityType(Dialog.ModalityType.MODELESS);
			d.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
			// createDialog only hides on OK; dispose so dismissed warnings don't
			// accumulate as hidden-but-live windows across a session.
			pane.addPropertyChangeListener(JOptionPane.VALUE_PROPERTY, ev -> d.dispose());
			d.setVisible(true); // modeless: returns immediately
		});
	}

	/** Disposes the dialog (safe to call from any thread). */
	void close() {
		SwingUtilities.invokeLater(() -> {
			if (dialog != null) {
				dialog.dispose();
			}
		});
	}
}
