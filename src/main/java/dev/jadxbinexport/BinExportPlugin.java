package dev.jadxbinexport;

import java.io.File;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.swing.JOptionPane;

import jadx.api.plugins.JadxPlugin;
import jadx.api.plugins.JadxPluginContext;
import jadx.api.plugins.JadxPluginInfo;
import jadx.api.plugins.gui.JadxGuiContext;
import jadx.api.plugins.pass.impl.SimpleAfterLoadPass;

/**
 * jadx plugin that exports the decompiler's analysis (classes, methods, control
 * flow graphs and call graph) into the BinExport2 protobuf format consumed by
 * BinDiff. This lets you diff/visualize two APKs' Dalvik bytecode in BinDiff.
 *
 * <p>Behaviour:
 * <ul>
 *   <li>CLI / library mode: exports automatically once inputs are loaded
 *       (registered as a {@link jadx.api.plugins.pass.types.JadxAfterLoadPass}).</li>
 *   <li>GUI mode: adds a "Plugins" menu action so the (potentially heavy) export
 *       runs on demand instead of on every project load.</li>
 * </ul>
 *
 * <p>Output path resolution (first match wins):
 * <ol>
 *   <li>plugin option {@code jadx-binexport.output} (or legacy
 *       {@code -Dbinexport.output=/path/to/out.BinExport})</li>
 *   <li>plugin option {@code jadx-binexport.outdir} (or legacy
 *       {@code -Dbinexport.outdir=/dir}) + {@code <input-basename>.BinExport}</li>
 *   <li>jadx output dir + {@code <input-basename>.BinExport}</li>
 * </ol>
 */
public class BinExportPlugin implements JadxPlugin {

	public static final String PLUGIN_ID = "jadx-binexport";

	@Override
	public JadxPluginInfo getPluginInfo() {
		return new JadxPluginInfo(
				PLUGIN_ID,
				"BinExport",
				"Export jadx analysis (methods, CFG, call graph) to BinDiff .BinExport (protobuf)");
	}

	@Override
	public void init(JadxPluginContext context) {
		BinExportOptions options = new BinExportOptions();
		context.registerOptions(options);
		JadxGuiContext gui = context.getGuiContext();
		if (gui != null) {
			// Fetch the decompiler at click time (the instance seen at init() can
			// be stale after a project reload) and refuse concurrent runs - two
			// exports would race on the same output file. Guard/dialog/worker
			// lifecycle lives in ExportProgressDialog.runGuarded, shared with the
			// diff action so the two can never drift apart again.
			AtomicBoolean running = new AtomicBoolean();
			gui.addMenuAction("Export to BinExport (.BinExport)", () ->
					ExportProgressDialog.runGuarded(gui, running, "Exporting to BinExport…",
							"BinExport", "Export failed",
							"An export is already running; wait for it to finish or cancel it.",
							"binexport-export", p -> {
								// Report where it went: the GUI has no out dir configured,
								// so the resolved path is not obvious and a log-only result
								// would be invisible without the Log Viewer.
								File out = Exporter.run(context.getDecompiler(), options, p);
								gui.uiRun(() -> JOptionPane.showMessageDialog(gui.getMainFrame(),
										"Exported to:\n" + out.getAbsolutePath(),
										"BinExport", JOptionPane.INFORMATION_MESSAGE));
							}));
			// Open another app's .BinExport and diff the currently-open app
			// against it (export + bindiff + browse) in one step, like the IDA
			// BinDiff plugin loading a second database.
			gui.addMenuAction("Open BinExport (.BinExport)...",
					() -> BinDiffResultsPanel.promptAndDiff(context, options));
		} else {
			context.addPass(new SimpleAfterLoadPass("BinExportPass",
					dec -> Exporter.runLogged(dec, options)));
		}
	}
}
