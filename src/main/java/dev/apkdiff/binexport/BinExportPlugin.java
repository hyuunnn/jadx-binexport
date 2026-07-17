package dev.apkdiff.binexport;

import jadx.api.JadxDecompiler;
import jadx.api.plugins.JadxPlugin;
import jadx.api.plugins.JadxPluginContext;
import jadx.api.plugins.JadxPluginInfo;
import jadx.api.plugins.gui.JadxGuiContext;

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
 *   <li>{@code -Dbinexport.output=/path/to/out.BinExport}</li>
 *   <li>{@code -Dbinexport.outdir=/dir} + {@code <input-basename>.BinExport}</li>
 *   <li>jadx output dir + {@code <input-basename>.BinExport}</li>
 * </ol>
 */
public class BinExportPlugin implements JadxPlugin {

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
		JadxGuiContext gui = context.getGuiContext();
		if (gui != null) {
			JadxDecompiler decompiler = context.getDecompiler();
			gui.addMenuAction("Export to BinExport (.BinExport)",
					() -> new Thread(() -> {
						try {
							Exporter.run(decompiler);
						} catch (Throwable t) {
							System.err.println("[BinExport] export failed: " + t);
							t.printStackTrace();
						}
					}, "binexport-export").start());
		} else {
			context.addPass(new BinExportPass());
		}
	}
}
