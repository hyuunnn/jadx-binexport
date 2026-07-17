package dev.apkdiff.binexport;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

import jadx.api.JadxDecompiler;

/**
 * Runs a diff of the currently-open app against another app's {@code .BinExport}
 * without leaving jadx: export the current app, invoke the {@code bindiff}
 * engine on the two files, and hand back the produced {@code .BinDiff}. This is
 * the in-GUI equivalent of the IDA BinDiff plugin's "diff against database".
 *
 * <p>The BinDiff matching engine itself is not reimplemented - we shell out to
 * the {@code bindiff} executable, which must be installed (configurable via the
 * {@code apk-diff-binexport.bindiff} option).
 */
final class BinDiffRunner {

	private BinDiffRunner() {
	}

	/** Thrown with a user-facing message when bindiff can't be located. */
	static final class BinDiffNotFound extends Exception {
		BinDiffNotFound(String msg) {
			super(msg);
		}
	}

	/**
	 * Exports the current app, diffs it against {@code otherBinExport}, and
	 * returns the resulting {@code .BinDiff}. Blocking; run off the EDT.
	 */
	static File diff(JadxDecompiler decompiler, File otherBinExport, BinExportOptions options)
			throws Exception {
		String bindiff = findBindiff(options);
		if (bindiff == null) {
			throw new BinDiffNotFound(
					"bindiff executable not found. Install BinDiff, or set the\n"
							+ "'apk-diff-binexport.bindiff' plugin option to its full path.");
		}

		Path work = Files.createTempDirectory("apkdiff-bindiff");
		File current = new File(work.toFile(), "current.BinExport");
		Exporter.runToFile(decompiler, current);

		Process p = new ProcessBuilder(bindiff,
				current.getAbsolutePath(), otherBinExport.getAbsolutePath(),
				"--output_dir", work.toString())
				.redirectErrorStream(true).start();
		String out = new String(p.getInputStream().readAllBytes());
		int code = p.waitFor();

		File result = firstBinDiff(work.toFile());
		if (result == null) {
			throw new IllegalStateException("bindiff produced no .BinDiff (exit " + code + "):\n" + out);
		}
		return result;
	}

	private static File firstBinDiff(File dir) {
		File[] files = dir.listFiles((d, name) -> name.endsWith(".BinDiff"));
		return files != null && files.length > 0 ? files[0] : null;
	}

	/** Locates bindiff: plugin option, then PATH, then common install paths. */
	static String findBindiff(BinExportOptions options) {
		String[] candidates = {
				options != null ? options.getBindiff() : null,
				"bindiff",
				"/usr/local/bin/bindiff",
				"/opt/bindiff/bin/bindiff",
				"C:\\Program Files\\BinDiff\\bin\\bindiff.exe",
		};
		for (String cmd : candidates) {
			if (cmd == null || cmd.isEmpty()) {
				continue;
			}
			try {
				// bindiff --help exits non-zero (Google-flags CLI), so detect by
				// output content rather than exit code.
				Process p = new ProcessBuilder(cmd, "--help").redirectErrorStream(true).start();
				String out = new String(p.getInputStream().readAllBytes());
				p.waitFor();
				if (out.toLowerCase(Locale.ROOT).contains("bindiff")) {
					return cmd;
				}
			} catch (Exception ignored) {
				// try next candidate
			}
		}
		return null;
	}
}
