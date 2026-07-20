package dev.jadxbinexport;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.File;
import java.util.List;
import java.util.Map;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.RowFilter;
import javax.swing.SortOrder;
import javax.swing.SwingConstants;
import javax.swing.WindowConstants;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.TableRowSorter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jadx.api.plugins.JadxPluginContext;
import jadx.api.plugins.gui.JadxGuiContext;
import jadx.core.dex.nodes.MethodNode;

/**
 * A navigable table of BinDiff match results inside jadx-gui: like the IDA
 * BinDiff plugin, double-clicking a row jumps to that function in the current
 * app. Loaded on demand from a {@code .BinDiff} file via the Plugins menu.
 */
final class BinDiffResultsPanel {

	private static final Logger LOG = LoggerFactory.getLogger(BinDiffResultsPanel.class);

	/** Guards the diff action so only one runs at a time (mirrors the export guard). */
	private static final java.util.concurrent.atomic.AtomicBoolean DIFF_RUNNING =
			new java.util.concurrent.atomic.AtomicBoolean();
	/** EDT-confined: true while the file chooser is up (see promptAndDiff). */
	private static boolean chooserOpen;

	private BinDiffResultsPanel() {
	}

	/**
	 * Menu entry point (IDA BinDiff-plugin style): open another app's {@code
	 * .BinExport}, diff the currently-open app against it via bindiff, and show
	 * the navigable results. jadx already holds one side (the open app), so only
	 * the OTHER side's export needs to be opened.
	 */
	static void promptAndDiff(JadxPluginContext context, BinExportOptions options) {
		JadxGuiContext gui = context.getGuiContext();
		if (gui == null) {
			return;
		}
		// jadx-gui dispatches menu-action runnables on its BACKGROUND executor,
		// not the EDT - and a JFileChooser realized off-EDT is the classic
		// intermittent-failure case (L&F model races). Marshal the chooser (and
		// the runGuarded kickoff) onto the EDT; runGuarded spawns its own worker
		// so no long work runs there.
		gui.uiRun(() -> {
			// The modal chooser PUMPS the EDT queue, so a double-invocation's
			// second posted lambda would open a second chooser on top of the
			// first (DIFF_RUNNING only engages after a file is picked). EDT-
			// confined flag; runGuarded's CAS stays the authoritative guard.
			if (chooserOpen || DIFF_RUNNING.get()) {
				JOptionPane.showMessageDialog(gui.getMainFrame(),
						"A diff is already being set up or running; finish or cancel it first.",
						"BinDiff", JOptionPane.INFORMATION_MESSAGE);
				return;
			}
			chooserOpen = true;
			try {
				JFileChooser chooser = new JFileChooser();
				chooser.setDialogTitle("Open the OTHER version's .BinExport to diff against this app");
				chooser.setFileFilter(new FileNameExtensionFilter("BinExport (*.BinExport)", "BinExport"));
				if (chooser.showOpenDialog(gui.getMainFrame()) != JFileChooser.APPROVE_OPTION) {
					return;
				}
				File other = chooser.getSelectedFile();
				diffAgainst(context, gui, options, other);
			} finally {
				chooserOpen = false;
			}
		});
	}

	/**
	 * One worker runs export -> bindiff -> read-results -> show, driving a
	 * single progress dialog (with Cancel) that also surfaces advisory warnings
	 * (imports-setting mismatch) modelessly. The one-at-a-time guard, dialog,
	 * worker thread and cancel/error/finally handling live in
	 * ExportProgressDialog.runGuarded, shared with the Export action.
	 */
	private static void diffAgainst(JadxPluginContext context, JadxGuiContext gui,
			BinExportOptions options, File other) {
		ExportProgressDialog.runGuarded(gui, DIFF_RUNNING,
				"Diff against " + other.getName(), "BinDiff", "Diff failed",
				"A diff is already running; wait for it to finish or cancel it.",
				"binexport-diff", progress -> {
					File binDiff = null;
					try {
						binDiff = BinDiffRunner.diff(context.getDecompiler(), other, options, progress);
						String name = binDiff.getName();
						progress.stage("Loading results…", 0);
						progress.throwIfCancelled();
						// Pass the sink so the (slow, on a big app) reads/class-walk poll cancel.
						List<BinDiffResults.Match> matches = BinDiffResults.loadMatches(binDiff, progress);
						BinDiffResults.Header header = BinDiffResults.loadHeader(binDiff);
						Map<String, MethodNode> index =
								BinDiffResults.methodIndex(context.getDecompiler(), progress);
						gui.uiRun(() -> show(gui, name, matches, header, index));
					} catch (BinDiffRunner.BinDiffNotFound nf) {
						// Friendlier than the generic failure dialog; returning normally
						// keeps runGuarded's Throwable handler out of it.
						gui.uiRun(() -> JOptionPane.showMessageDialog(gui.getMainFrame(),
								nf.getMessage(), "BinDiff not found", JOptionPane.WARNING_MESSAGE));
					} finally {
						// The .BinDiff is fully read by now (show() only needs its name), so
						// drop it eagerly instead of leaving it for JVM-exit deleteOnExit.
						if (binDiff != null) {
							binDiff.delete();
						}
					}
				});
	}

	private static void show(JadxGuiContext gui, String fileName, List<BinDiffResults.Match> matches,
			BinDiffResults.Header header, Map<String, MethodNode> index) {
		ResultsTableModel model = new ResultsTableModel(matches, index);
		if (model.getRowCount() == 0) {
			JOptionPane.showMessageDialog(gui.getMainFrame(),
					"No matched functions from this file belong to the currently-open app.\n"
							+ "Make sure this .BinDiff was produced from an export of the same app+jadx version.",
					"BinDiff results", JOptionPane.INFORMATION_MESSAGE);
			return;
		}

		JTable table = buildNavigableTable(model, node -> {
			if (!gui.open(node)) {
				LOG.warn("[BinExport] could not navigate to selected function");
			}
		});
		@SuppressWarnings("unchecked")
		TableRowSorter<ResultsTableModel> sorter = (TableRowSorter<ResultsTableModel>) table.getRowSorter();

		JTextField filter = new JTextField();
		// Filtering re-runs the RowFilter over every row and re-sorts, on the EDT.
		// So (a) coalesce a burst of keystrokes into ONE pass ~200ms after typing
		// stops (a large result table would otherwise re-filter+re-sort on every
		// character, freezing input), and (b) match against the model's precomputed
		// lowercase names via plain contains() instead of compiling a regex per
		// keystroke. The Swing Timer fires on the EDT, where setRowFilter must run.
		javax.swing.Timer debounce = new javax.swing.Timer(200, e -> {
			String needle = filter.getText().trim().toLowerCase(java.util.Locale.ROOT);
			sorter.setRowFilter(needle.isEmpty() ? null
					: new RowFilter<ResultsTableModel, Integer>() {
						@Override
						public boolean include(Entry<? extends ResultsTableModel, ? extends Integer> entry) {
							return model.matches(entry.getIdentifier(), needle);
						}
					});
		});
		debounce.setRepeats(false);
		filter.getDocument().addDocumentListener(new DocumentListener() {
			public void insertUpdate(DocumentEvent e) {
				debounce.restart();
			}

			public void removeUpdate(DocumentEvent e) {
				debounce.restart();
			}

			public void changedUpdate(DocumentEvent e) {
				debounce.restart();
			}
		});

		JPanel top = new JPanel();
		top.setLayout(new BoxLayout(top, BoxLayout.X_AXIS));
		top.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));
		String sides = header != null ? header.primary + "  ↔  " + header.secondary : fileName;
		top.add(new JLabel(model.getRowCount() + " matched functions in this app   (" + sides + ")"));
		top.add(Box.createHorizontalGlue());
		top.add(new JLabel("Filter: "));
		filter.setMaximumSize(new Dimension(260, 26));
		top.add(filter);

		JLabel hint = new JLabel("Double-click (or Enter) a row to open that function in jadx.");
		hint.setBorder(BorderFactory.createEmptyBorder(4, 8, 6, 8));

		JPanel content = new JPanel(new BorderLayout());
		content.add(top, BorderLayout.NORTH);
		content.add(new JScrollPane(table), BorderLayout.CENTER);
		content.add(hint, BorderLayout.SOUTH);

		JDialog dialog = new JDialog(gui.getMainFrame(), "BinDiff results — " + fileName, false);
		// DISPOSE_ON_CLOSE is load-bearing: the default HIDE_ON_CLOSE only hides,
		// and AWT keeps every displayable (shown, undisposed) window strongly
		// reachable via its static allWindows list until dispose() destroys the
		// peer - this dialog's table model holds MethodNodes -> ClassNode ->
		// RootNode, i.e. the ENTIRE decompiled model, so a hidden-not-disposed
		// results window would pin hundreds of MB per diff for the whole session.
		dialog.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
		dialog.setContentPane(content);
		dialog.setSize(820, 520);
		dialog.setLocationRelativeTo(gui.getMainFrame());
		dialog.setVisible(true);
	}

	/**
	 * Builds the results table with sorting, coloring, and double-click / Enter
	 * navigation wired to {@code onOpen}. Package-private so a test can drive the
	 * exact click path without a jadx-gui or a JDialog.
	 */
	static JTable buildNavigableTable(ResultsTableModel model, java.util.function.Consumer<MethodNode> onOpen) {
		JTable table = new JTable(model);
		table.setAutoCreateRowSorter(false);
		TableRowSorter<ResultsTableModel> sorter = new TableRowSorter<>(model);
		table.setRowSorter(sorter);
		// Most useful default: changed functions (lowest similarity) first.
		sorter.setSortKeys(List.of(new javax.swing.RowSorter.SortKey(2, SortOrder.ASCENDING)));
		table.getColumnModel().getColumn(2).setCellRenderer(new SimilarityRenderer());
		table.getColumnModel().getColumn(3).setCellRenderer(new DecimalRenderer());
		table.getColumnModel().getColumn(0).setPreferredWidth(320);
		table.getColumnModel().getColumn(1).setPreferredWidth(320);
		table.getColumnModel().getColumn(2).setMaxWidth(90);
		table.getColumnModel().getColumn(3).setMaxWidth(90);
		table.setFillsViewportHeight(true);

		Runnable navigate = () -> {
			int viewRow = table.getSelectedRow();
			if (viewRow < 0) {
				return;
			}
			MethodNode target = model.methodAt(table.convertRowIndexToModel(viewRow));
			if (target != null) {
				onOpen.accept(target);
			}
		};
		table.addMouseListener(new MouseAdapter() {
			@Override
			public void mouseClicked(MouseEvent e) {
				if (e.getClickCount() == 2) {
					navigate.run();
				}
			}
		});
		table.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT)
				.put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "binexport-open");
		table.getActionMap().put("binexport-open", new javax.swing.AbstractAction() {
			@Override
			public void actionPerformed(java.awt.event.ActionEvent e) {
				navigate.run();
			}
		});
		return table;
	}

	/** Table model over the matches that resolve to a method in this session. */
	static final class ResultsTableModel extends AbstractTableModel {
		private static final String[] COLS = {"Function (this app)", "Matched in other version", "Similarity", "Confidence"};

		private final List<String> local = new java.util.ArrayList<>();
		private final List<String> other = new java.util.ArrayList<>();
		private final List<Double> similarity = new java.util.ArrayList<>();
		private final List<Double> confidence = new java.util.ArrayList<>();
		private final List<MethodNode> targets = new java.util.ArrayList<>();
		// Lowercased copies of the two name columns, computed once here so filtering
		// is a couple of allocation-free contains() per row (no per-keystroke
		// lowercasing or regex). Checked separately (not concatenated) so a query
		// can never match across the two names. Parallel to the columns above.
		private final List<String> localLower = new java.util.ArrayList<>();
		private final List<String> otherLower = new java.util.ArrayList<>();

		ResultsTableModel(List<BinDiffResults.Match> matches, Map<String, MethodNode> index) {
			for (BinDiffResults.Match m : matches) {
				MethodNode target = BinDiffResults.resolveLocal(m, index);
				if (target == null) {
					continue; // this pair's functions aren't in the open app
				}
				String localName = target.getMethodInfo().getRawFullId();
				String otherName = BinDiffResults.counterpart(m, index);
				local.add(localName);
				other.add(otherName);
				similarity.add(m.similarity);
				confidence.add(m.confidence);
				targets.add(target);
				localLower.add(localName.toLowerCase(java.util.Locale.ROOT));
				otherLower.add(otherName.toLowerCase(java.util.Locale.ROOT));
			}
		}

		/** True if either function name of this row contains the already-lowercased query. */
		boolean matches(int modelRow, String needleLower) {
			return localLower.get(modelRow).contains(needleLower)
					|| otherLower.get(modelRow).contains(needleLower);
		}

		MethodNode methodAt(int modelRow) {
			return targets.get(modelRow);
		}

		@Override
		public int getRowCount() {
			return targets.size();
		}

		@Override
		public int getColumnCount() {
			return COLS.length;
		}

		@Override
		public String getColumnName(int c) {
			return COLS[c];
		}

		@Override
		public Class<?> getColumnClass(int c) {
			return c >= 2 ? Double.class : String.class;
		}

		@Override
		public boolean isCellEditable(int r, int c) {
			return false;
		}

		@Override
		public Object getValueAt(int r, int c) {
			switch (c) {
				case 0: return local.get(r);
				case 1: return other.get(r);
				case 2: return similarity.get(r);
				default: return confidence.get(r);
			}
		}
	}

	/** Right-aligned, 2-decimal number cell (no coloring). */
	private static final class DecimalRenderer extends DefaultTableCellRenderer {
		DecimalRenderer() {
			setHorizontalAlignment(SwingConstants.RIGHT);
		}

		@Override
		protected void setValue(Object value) {
			setText(value instanceof Double
					? String.format(java.util.Locale.ROOT, "%.2f", (Double) value)
					: String.valueOf(value));
		}
	}

	/** Shades similarity from red (changed) to green (identical), 2 decimals. */
	private static final class SimilarityRenderer extends DefaultTableCellRenderer {
		SimilarityRenderer() {
			setHorizontalAlignment(SwingConstants.RIGHT);
		}

		@Override
		public Component getTableCellRendererComponent(JTable table, Object value, boolean selected,
				boolean focus, int row, int col) {
			Component c = super.getTableCellRendererComponent(table, value, selected, focus, row, col);
			double s = value instanceof Double ? (Double) value : 1.0;
			setText(String.format(java.util.Locale.ROOT, "%.2f", s));
			if (!selected) {
				float hue = (float) (Math.max(0.0, Math.min(1.0, s)) * 0.33); // 0=red .. 0.33=green
				c.setBackground(Color.getHSBColor(hue, 0.18f, 0.98f));
				// The tint is always near-white; pin a dark foreground so the cell
				// stays readable under dark themes (whose default table foreground
				// is near-white, which would render white-on-white here).
				c.setForeground(Color.BLACK);
			}
			return c;
		}
	}
}
