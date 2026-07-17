package dev.apkdiff.binexport;

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
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.TableRowSorter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jadx.api.JadxDecompiler;
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

	private BinDiffResultsPanel() {
	}

	/** Menu entry point: pick a .BinDiff file, load it, show the results table. */
	static void promptAndShow(JadxPluginContext context) {
		JadxGuiContext gui = context.getGuiContext();
		if (gui == null) {
			return;
		}
		JFileChooser chooser = new JFileChooser();
		chooser.setDialogTitle("Open BinDiff results (.BinDiff)");
		chooser.setFileFilter(new FileNameExtensionFilter("BinDiff results (*.BinDiff)", "BinDiff"));
		if (chooser.showOpenDialog(gui.getMainFrame()) != JFileChooser.APPROVE_OPTION) {
			return;
		}
		File file = chooser.getSelectedFile();
		// Parse + index off the EDT; the DB read and class walk can be slow.
		new Thread(() -> {
			try {
				List<BinDiffResults.Match> matches = BinDiffResults.loadMatches(file);
				BinDiffResults.Header header = BinDiffResults.loadHeader(file);
				Map<String, MethodNode> index = BinDiffResults.methodIndex(context.getDecompiler());
				gui.uiRun(() -> show(gui, file, matches, header, index));
			} catch (Throwable t) {
				LOG.error("[BinExport] failed to load BinDiff results from {}", file, t);
				gui.uiRun(() -> JOptionPane.showMessageDialog(gui.getMainFrame(),
						"Failed to read BinDiff results:\n" + t.getMessage(),
						"BinDiff results", JOptionPane.ERROR_MESSAGE));
			}
		}, "binexport-load-results").start();
	}

	private static void show(JadxGuiContext gui, File file, List<BinDiffResults.Match> matches,
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
		filter.getDocument().addDocumentListener(new DocumentListener() {
			private void apply() {
				String q = filter.getText().trim();
				sorter.setRowFilter(q.isEmpty() ? null : RowFilter.regexFilter("(?i)" + java.util.regex.Pattern.quote(q)));
			}

			public void insertUpdate(DocumentEvent e) {
				apply();
			}

			public void removeUpdate(DocumentEvent e) {
				apply();
			}

			public void changedUpdate(DocumentEvent e) {
				apply();
			}
		});

		JPanel top = new JPanel();
		top.setLayout(new BoxLayout(top, BoxLayout.X_AXIS));
		top.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));
		String sides = header != null ? header.primary + "  ↔  " + header.secondary : file.getName();
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

		JDialog dialog = new JDialog(gui.getMainFrame(), "BinDiff results — " + file.getName(), false);
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

		ResultsTableModel(List<BinDiffResults.Match> matches, Map<String, MethodNode> index) {
			for (BinDiffResults.Match m : matches) {
				MethodNode target = BinDiffResults.resolveLocal(m, index);
				if (target == null) {
					continue; // this pair's functions aren't in the open app
				}
				local.add(target.getMethodInfo().getRawFullId());
				other.add(BinDiffResults.counterpart(m, index));
				similarity.add(m.similarity);
				confidence.add(m.confidence);
				targets.add(target);
			}
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
			}
			return c;
		}
	}
}
