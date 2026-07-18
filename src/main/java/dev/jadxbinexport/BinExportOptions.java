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
 *   <li>{@code jadx-binexport.imports} - also emit IMPORTED call-graph vertices
 *       + edges for external (framework/library) calls, for richer diff matching
 *       (off by default; larger output)</li>
 * </ul>
 *
 * <p>The old JVM-global system properties {@code binexport.output} /
 * {@code binexport.outdir} / {@code binexport.strict} / {@code binexport.imports}
 * are still honored as a fallback, but plugin options are scoped to one
 * decompiler instance and therefore safe when several instances run in the same
 * JVM.
 */
public class BinExportOptions extends BasePluginOptionsBuilder {

	private String output;
	private String outDir;
	private String bindiff;
	// Tri-state (null = not set): an explicit =false must beat a JVM-global
	// legacy sysprop =true, which an OR over a primitive boolean cannot express.
	// No defaultValue is registered, so jadx's parseOption passes null through
	// for absent keys; the null-safe formatter shows the effective default.
	private Boolean strict;
	private Boolean imports;

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
				.formatter(v -> v == null ? "false" : v.toString())
				.setter(v -> strict = v);
		boolOption(BinExportPlugin.PLUGIN_ID + ".imports")
				.description("also emit IMPORTED vertices/edges for external calls (richer diff, larger output)")
				.formatter(v -> v == null ? "false" : v.toString())
				.setter(v -> imports = v);
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
		return boolWithProp(strict, "binexport.strict");
	}

	/** True if external (framework/library) calls should get IMPORTED vertices + edges. */
	public boolean isImports() {
		return boolWithProp(imports, "binexport.imports");
	}

	/**
	 * A tri-state boolean option with its legacy {@code -D} system-property
	 * fallback: an explicitly set option (true OR false) wins; the sysprop is
	 * consulted only when the option is unset. An OR would silently let a
	 * JVM-global {@code -D...=true} override an explicit {@code -P...=false},
	 * making the sysprop an override instead of the documented fallback.
	 */
	private static boolean boolWithProp(Boolean value, String prop) {
		return value != null ? value : Boolean.parseBoolean(System.getProperty(prop));
	}

	private static String firstNonEmpty(String value, String fallback) {
		return value != null && !value.isEmpty() ? value : fallback;
	}
}
