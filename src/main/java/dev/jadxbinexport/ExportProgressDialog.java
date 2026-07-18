package dev.jadxbinexport;

import java.awt.BorderLayout;
import java.awt.Dialog;
import java.awt.Dimension;
import java.awt.GraphicsConfiguration;
import java.awt.Rectangle;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jadx.api.plugins.gui.JadxGuiContext;

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

	private static final Logger LOG = LoggerFactory.getLogger(ExportProgressDialog.class);

	private final JFrame parent;
	private final String title;

	private JDialog dialog;
	private JProgressBar bar;
	private JLabel note;
	private final List<JDialog> warnings = new ArrayList<>();
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
	 * Warnings are tracked so {@link #close()} can dispose any the user has not
	 * dismissed when the flow was CANCELLED (an untracked one would orphan;
	 * on success/failure the advisory deliberately stays up - see close()), and
	 * placed below this dialog from live geometry so they don't land exactly on
	 * its Cancel button (both center on the parent).
	 */
	@Override
	public void warn(String message) {
		SwingUtilities.invokeLater(() -> {
			JOptionPane pane = new JOptionPane(message, JOptionPane.WARNING_MESSAGE);
			JDialog d = pane.createDialog(parent, title + " — warning");
			d.setModalityType(Dialog.ModalityType.MODELESS);
			d.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
			// createDialog only hides on OK; dispose so dismissed warnings don't
			// accumulate as hidden-but-live windows across a session.
			pane.addPropertyChangeListener(JOptionPane.VALUE_PROPERTY, ev -> d.dispose());
			int cascade = warnings.size() * 24; // de-stack multiple undismissed warnings
			warnings.add(d); // EDT-confined, like all state here
			d.addWindowListener(new WindowAdapter() {
				@Override
				public void windowClosed(WindowEvent e) {
					warnings.remove(d);
				}
			});
			// Place below the progress dialog from LIVE geometry (both center on
			// the parent, and a magic pixel offset would silently re-cover the
			// Cancel button if the dialog were ever resized); build() has run by
			// now (FIFO EDT posts), so `dialog` is set. Clamp to the screen.
			int y = dialog.getY() + dialog.getHeight() + 12 + cascade;
			GraphicsConfiguration gc = parent.getGraphicsConfiguration();
			if (gc != null) {
				Rectangle screen = gc.getBounds();
				y = Math.min(y, screen.y + screen.height - d.getHeight());
			}
			d.setLocation(d.getX(), y);
			d.setVisible(true); // modeless: returns immediately
		});
	}

	/** The work a {@link #runGuarded guarded GUI action} runs on its worker thread. */
	interface GuardedWork {
		void run(ExportProgressDialog progress) throws Exception;
	}

	/**
	 * THE guarded-GUI-worker lifecycle both menu actions share (their two
	 * hand-rolled copies had already drifted once: the diff action's dialog
	 * ctor escaped the guarded try, so a ctor throw would have latched its
	 * guard and killed the menu until restart). Contract: reject re-entry via
	 * {@code guard} (optionally telling the user via {@code busyMessage});
	 * build the dialog INSIDE the try that resets the guard; run {@code work}
	 * on a named worker thread; {@link Exporter.CancelledException} is an info
	 * log only, any other Throwable is an error log + error dialog; a finally
	 * always closes the dialog and clears the guard; ctor/start() failures are
	 * covered by the same outer try. Action-specific behavior (extra catches,
	 * temp-file cleanup, success dialogs) lives inside {@code work}.
	 */
	static void runGuarded(JadxGuiContext gui, AtomicBoolean guard, String dialogTitle,
			String errorTitle, String failPrefix, String busyMessage, String threadName,
			GuardedWork work) {
		if (!guard.compareAndSet(false, true)) {
			LOG.warn("[BinExport] {} rejected: already running", threadName);
			if (busyMessage != null) {
				JOptionPane.showMessageDialog(gui.getMainFrame(), busyMessage,
						errorTitle, JOptionPane.INFORMATION_MESSAGE);
			}
			return;
		}
		ExportProgressDialog progress = null;
		try {
			progress = new ExportProgressDialog(gui.getMainFrame(), dialogTitle);
			ExportProgressDialog p = progress;
			new Thread(() -> {
				try {
					work.run(p);
				} catch (Exporter.CancelledException c) {
					LOG.info("[BinExport] {} cancelled by user", threadName);
				} catch (Throwable t) {
					LOG.error("[BinExport] {} failed", threadName, t);
					gui.uiRun(() -> JOptionPane.showMessageDialog(gui.getMainFrame(),
							failPrefix + ":\n" + t.getMessage(),
							errorTitle, JOptionPane.ERROR_MESSAGE));
				} finally {
					p.close();
					guard.set(false);
				}
			}, threadName).start();
		} catch (Throwable t) {
			if (progress != null) {
				progress.close();
			}
			guard.set(false);
			throw t;
		}
	}

	/**
	 * Disposes the dialog (any thread). Undismissed warnings are disposed only
	 * on a CANCELLED flow (they would otherwise orphan - each flow builds a
	 * fresh dialog, so a later close() can never clean up a prior flow's
	 * leftovers). On success or failure the advisory stays up until the user
	 * dismisses it: it is most relevant exactly when the results (or a retry
	 * decision) are on screen, and auto-disposing it the moment results appear
	 * would defeat its purpose for a user who stepped away during the run.
	 */
	void close() {
		SwingUtilities.invokeLater(() -> {
			if (dialog != null) {
				dialog.dispose();
			}
			if (cancelled) {
				// Snapshot: dispose fires windowClosed, which removes from the list.
				for (JDialog w : new ArrayList<>(warnings)) {
					w.dispose();
				}
			}
		});
	}
}
