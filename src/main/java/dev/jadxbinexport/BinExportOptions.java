package dev.jadxbinexport;

import jadx.api.plugins.options.impl.BasePluginOptionsBuilder;

/**
 * Per-decompiler-instance plugin options, visible in {@code jadx plugins} and
 * the GUI preferences dialog:
 *
 * <ul>
 *   <li>{@code jadx-binexport.output} - explicit output file path</li>
 *   <li>{@code jadx-binexport.outdir} - output directory for
 *       {@code <input-basename>.BinExport}</li>
 *   <li>{@code jadx-binexport.strict} - fail the run (non-zero exit) if the
 *       export fails, for CI pipelines</li>
 * </ul>
 *
 * <p>The old JVM-global system properties {@code binexport.output} /
 * {@code binexport.outdir} / {@code binexport.strict} are still honored as a
 * fallback, but plugin options are scoped to one decompiler instance and
 * therefore safe when several instances run in the same JVM.
 */
public class BinExportOptions extends BasePluginOptionsBuilder {

	private String output;
	private String outDir;
	private String bindiff;
	private boolean strict;

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
		boolOption(BinExportPlugin.PLUGIN_ID + ".strict")
				.description("fail the run (non-zero exit) if the export fails, for CI")
				.defaultValue(false)
				.setter(v -> strict = v);
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

	/** True if a failed export should abort the run (plugin option or legacy sysprop). */
	public boolean isStrict() {
		return strict || Boolean.parseBoolean(System.getProperty("binexport.strict"));
	}

	private static String firstNonEmpty(String value, String fallback) {
		return value != null && !value.isEmpty() ? value : fallback;
	}
}
