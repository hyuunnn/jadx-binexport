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
	// Live warning dialogs across ALL flows (EDT-confined): close() deliberately
	// leaves advisories up on success/failure, so a later flow must place its
	// warning below every prior flow's undismissed one or the two land
	// pixel-exact on top of each other (identical parent-centered geometry) and
	// the lower one looks undismissable. A LIST of live windows, not a count:
	// deriving an offset from a count re-created the overlap whenever warnings
	// were dismissed out of order (the count compacts, window positions don't).
	private static final List<JDialog> LIVE_WARNINGS = new ArrayList<>();
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
	 * Warnings are tracked so {@link #close(boolean)} can dispose any the user
	 * has not dismissed when the flow actually ENDED cancelled (outcome passed
	 * in by runGuarded; an untracked one would orphan - on success/failure the
	 * advisory deliberately stays up, see close), and placed below this dialog
	 * and every live warning from actual window bounds so they never land on
	 * the Cancel button or on each other (all center on the parent).
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
			warnings.add(d); // EDT-confined, like all state here
			LIVE_WARNINGS.add(d);
			d.addWindowListener(new WindowAdapter() {
				@Override
				public void windowClosed(WindowEvent e) {
					LIVE_WARNINGS.remove(d);
					warnings.remove(d);
				}
			});
			// Place below the progress dialog AND every live warning, from ACTUAL
			// window bounds (both center on the parent; a magic pixel offset would
			// silently re-cover the Cancel button on a resize, and a count-derived
			// cascade overlaps after out-of-order dismissals). `dialog` is set by
			// build()'s first statement (FIFO EDT posts) unless build() itself
			// threw ON THE EDT after a successful ctor - a ctor throw would have
			// made runGuarded bail before any warn - so fall back to a
			// parent-relative offset then, matching the sibling null guards.
			int base = dialog != null ? dialog.getY() + dialog.getHeight() : d.getY() + 158;
			for (JDialog w : LIVE_WARNINGS) {
				if (w != d && w.isDisplayable()) {
					base = Math.max(base, w.getY() + w.getHeight());
				}
			}
			int y = base + 12;
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
	 * {@code guard}, telling the user via {@code busyMessage} (a log-only
	 * rejection is invisible in the GUI - the doctrine this codebase applies
	 * everywhere else); build the dialog INSIDE the try that resets the guard;
	 * run {@code work} on a named worker thread; {@link
	 * Exporter.CancelledException} is an info log only, any other Throwable is
	 * an error log + error dialog; a finally always closes the dialog (passing
	 * whether the flow actually ended cancelled, see {@link #close(boolean)})
	 * and clears the guard; ctor/start() failures are covered by the same
	 * outer try. NOTE: jadx-gui dispatches menu-action runnables on its
	 * BACKGROUND executor, not the EDT, so every Swing touch here marshals via
	 * {@code gui.uiRun}/invokeLater. Action-specific behavior (extra catches,
	 * temp-file cleanup, success dialogs) lives inside {@code work}.
	 */
	static void runGuarded(JadxGuiContext gui, AtomicBoolean guard, String dialogTitle,
			String errorTitle, String failPrefix, String busyMessage, String threadName,
			GuardedWork work) {
		if (!guard.compareAndSet(false, true)) {
			LOG.warn("[BinExport] {} rejected: already running", threadName);
			gui.uiRun(() -> JOptionPane.showMessageDialog(gui.getMainFrame(), busyMessage,
					errorTitle, JOptionPane.INFORMATION_MESSAGE));
			return;
		}
		ExportProgressDialog progress = null;
		try {
			progress = new ExportProgressDialog(gui.getMainFrame(), dialogTitle);
			ExportProgressDialog p = progress;
			new Thread(() -> {
				boolean flowCancelled = false;
				try {
					work.run(p);
				} catch (Exporter.CancelledException c) {
					flowCancelled = true;
					LOG.info("[BinExport] {} cancelled by user", threadName);
				} catch (Throwable t) {
					// dialogTitle carries the action context (e.g. which .BinExport
					// a diff ran against) - the on-screen title is not in the log.
					LOG.error("[BinExport] {} ({}) failed", threadName, dialogTitle, t);
					gui.uiRun(() -> JOptionPane.showMessageDialog(gui.getMainFrame(),
							failPrefix + ":\n" + t.getMessage(),
							errorTitle, JOptionPane.ERROR_MESSAGE));
				} finally {
					p.close(flowCancelled);
					guard.set(false);
				}
			}, threadName).start();
		} catch (Throwable t) {
			if (progress != null) {
				progress.close(false); // no work ran, so no warnings exist yet
			}
			guard.set(false);
			throw t;
		}
	}

	/**
	 * Disposes the dialog (any thread). Undismissed warnings are disposed only
	 * when the flow actually ENDED cancelled - not on the raw Cancel-button
	 * flag, which can be set after the work's last poll point and would then
	 * wrongly dispose the advisory alongside successfully shown results. On
	 * success or failure the advisory stays up until the user dismisses it: it
	 * is most relevant exactly when the results (or a retry decision) are on
	 * screen, and auto-disposing it the moment results appear would defeat its
	 * purpose for a user who stepped away during the run. (On cancel it must
	 * go: each flow builds a fresh dialog, so a later close() could never
	 * clean up a prior flow's orphan.)
	 */
	void close(boolean flowCancelled) {
		SwingUtilities.invokeLater(() -> {
			if (dialog != null) {
				dialog.dispose();
			}
			if (flowCancelled) {
				// Snapshot: dispose fires windowClosed, which removes from the list.
				for (JDialog w : new ArrayList<>(warnings)) {
					w.dispose();
				}
			}
		});
	}
}
