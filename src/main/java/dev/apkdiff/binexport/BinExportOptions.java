package dev.apkdiff.binexport;

import jadx.api.plugins.options.impl.BasePluginOptionsBuilder;

/**
 * Per-decompiler-instance plugin options, visible in {@code jadx plugins} and
 * the GUI preferences dialog:
 *
 * <ul>
 *   <li>{@code apk-diff-binexport.output} - explicit output file path</li>
 *   <li>{@code apk-diff-binexport.outdir} - output directory for
 *       {@code <input-basename>.BinExport}</li>
 * </ul>
 *
 * <p>The old JVM-global system properties {@code binexport.output} /
 * {@code binexport.outdir} are still honored as a fallback, but plugin options
 * are scoped to one decompiler instance and therefore safe when several
 * instances run in the same JVM.
 */
public class BinExportOptions extends BasePluginOptionsBuilder {

	private String output;
	private String outDir;
	private String bindiff;

	@Override
	public void registerOptions() {
		strOption(BinExportPlugin.PLUGIN_ID + ".output")
				.description("explicit output file path for the .BinExport")
				.defaultValue("")
				.setter(v -> output = v);
		strOption(BinExportPlugin.PLUGIN_ID + ".outdir")
				.description("output directory for <input-basename>.BinExport")
				.defaultValue("")
				.setter(v -> outDir = v);
		strOption(BinExportPlugin.PLUGIN_ID + ".bindiff")
				.description("path to the bindiff executable (for in-GUI diffing)")
				.defaultValue("")
				.setter(v -> bindiff = v);
	}

	public String getOutput() {
		return firstNonEmpty(output, System.getProperty("binexport.output"));
	}

	public String getOutDir() {
		return firstNonEmpty(outDir, System.getProperty("binexport.outdir"));
	}

	public String getBindiff() {
		return firstNonEmpty(bindiff, System.getProperty("binexport.bindiff"));
	}

	private static String firstNonEmpty(String value, String fallback) {
		return value != null && !value.isEmpty() ? value : fallback;
	}
}
