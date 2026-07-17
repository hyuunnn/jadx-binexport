package dev.apkdiff.binexport;

import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
 *   <li>plugin option {@code apk-diff-binexport.output} (or legacy
 *       {@code -Dbinexport.output=/path/to/out.BinExport})</li>
 *   <li>plugin option {@code apk-diff-binexport.outdir} (or legacy
 *       {@code -Dbinexport.outdir=/dir}) + {@code <input-basename>.BinExport}</li>
 *   <li>jadx output dir + {@code <input-basename>.BinExport}</li>
 * </ol>
 */
public class BinExportPlugin implements JadxPlugin {

	private static final Logger LOG = LoggerFactory.getLogger(BinExportPlugin.class);

	public static final String PLUGIN_ID = "apk-diff-binexport";

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
			// exports would race on the same output file.
			AtomicBoolean running = new AtomicBoolean();
			gui.addMenuAction("Export to BinExport (.BinExport)", () -> {
				if (!running.compareAndSet(false, true)) {
					LOG.warn("[BinExport] export already in progress");
					return;
				}
				try {
					new Thread(() -> {
						try {
							Exporter.runLogged(context.getDecompiler(), options);
						} finally {
							running.set(false);
						}
					}, "binexport-export").start();
				} catch (Throwable t) {
					// If the thread never started, the finally above never runs -
					// without this reset the menu action would be dead until restart.
					running.set(false);
					throw t;
				}
			});
			// Load a BinDiff results DB and browse matches with click-to-navigate,
			// like the IDA BinDiff plugin.
			gui.addMenuAction("Open BinDiff results (.BinDiff)...",
					() -> BinDiffResultsPanel.promptAndShow(context));
		} else {
			context.addPass(new SimpleAfterLoadPass("BinExportPass",
					dec -> Exporter.runLogged(dec, options)));
		}
	}
}
