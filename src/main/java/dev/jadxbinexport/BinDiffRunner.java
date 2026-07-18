package dev.jadxbinexport;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jadx.api.JadxDecompiler;

/**
 * Runs a diff of the currently-open app against another app's {@code .BinExport}
 * without leaving jadx: export the current app, invoke the {@code bindiff}
 * engine on the two files, and hand back the produced {@code .BinDiff}. This is
 * the in-GUI equivalent of the IDA BinDiff plugin's "diff against database".
 *
 * <p>The BinDiff matching engine itself is not reimplemented - we shell out to
 * the {@code bindiff} executable, which must be installed (configurable via the
 * {@code jadx-binexport.bindiff} option).
 */
final class BinDiffRunner {

	private static final Logger LOG = LoggerFactory.getLogger(BinDiffRunner.class);

	/** Upper bound on a full diff; a wedged bindiff must not hang the GUI forever. */
	private static final long DIFF_TIMEOUT_MIN = 30;
	/** Upper bound on a single --help probe during executable discovery. */
	private static final long PROBE_TIMEOUT_SEC = 20;

	private BinDiffRunner() {
	}

	/** Captured output and exit code of a finished subprocess. */
	private static final class ProcResult {
		final int exitCode;
		final String output;

		ProcResult(int exitCode, String output) {
			this.exitCode = exitCode;
			this.output = output;
		}
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
		return diff(decompiler, otherBinExport, options, ExportProgress.NONE);
	}

	static File diff(JadxDecompiler decompiler, File otherBinExport, BinExportOptions options,
			ExportProgress progress) throws Exception {
		String bindiff = resolveBindiff(options);

		Path work = Files.createTempDirectory("jadx-binexport");
		try {
			File current = new File(work.toFile(), "current.BinExport");
			Exporter.runToFile(decompiler, current, progress);

			// bindiff has no progress protocol, so show it as an indeterminate stage;
			// runProcess polls progress.cancelled() so Cancel kills a running bindiff.
			progress.stage("Running BinDiff…", 0);
			ProcResult res = runProcess(TimeUnit.MINUTES.toSeconds(DIFF_TIMEOUT_MIN), progress, bindiff,
					current.getAbsolutePath(), otherBinExport.getAbsolutePath(),
					"--output_dir", work.toString());

			File result = firstBinDiff(work.toFile());
			if (result == null) {
				throw new IllegalStateException(
						"bindiff produced no .BinDiff (exit " + res.exitCode + "):\n" + res.output);
			}
			// A non-zero exit with a file present may mean a partial/truncated DB;
			// surface it rather than silently treating the result as complete.
			if (res.exitCode != 0) {
				LOG.warn("[BinExport] bindiff exited {} but produced {}; results may be incomplete:\n{}",
						res.exitCode, result.getName(), res.output);
			}
			// Copy the small .BinDiff out so the whole work dir (which holds the
			// multi-MB current.BinExport) can be deleted now instead of leaking.
			File out = File.createTempFile("jadx-bindiff", ".BinDiff");
			out.deleteOnExit();
			Files.copy(result.toPath(), out.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
			return out;
		} finally {
			deleteRecursively(work);
		}
	}

	/**
	 * Resolves the bindiff executable. An explicitly configured
	 * {@code jadx-binexport.bindiff} is authoritative: if it does not run, fail
	 * with a message naming it rather than silently falling back to a different
	 * bindiff on PATH. Otherwise auto-detect via PATH and common install paths.
	 */
	private static String resolveBindiff(BinExportOptions options) throws BinDiffNotFound {
		String explicit = options != null ? options.getBindiff() : null;
		if (explicit != null && !explicit.isEmpty()) {
			if (!probe(explicit)) {
				throw new BinDiffNotFound("configured 'jadx-binexport.bindiff' = '" + explicit
						+ "' did not run as bindiff. Fix the option or clear it to auto-detect.");
			}
			return explicit;
		}
		String found = findBindiff(options);
		if (found == null) {
			throw new BinDiffNotFound(
					"bindiff executable not found. Install BinDiff, or set the\n"
							+ "'jadx-binexport.bindiff' plugin option to its full path.");
		}
		return found;
	}

	private static File firstBinDiff(File dir) {
		File[] files = dir.listFiles((d, name) -> name.endsWith(".BinDiff"));
		return files != null && files.length > 0 ? files[0] : null;
	}

	/**
	 * Best-effort auto-detection: PATH, then common install paths. Reached from
	 * {@link #resolveBindiff} only when no explicit option is set (that path is
	 * handled authoritatively upstream), so the option candidate below matters
	 * only for direct callers/tests that pass one.
	 */
	static String findBindiff(BinExportOptions options) {
		String[] candidates = {
				options != null ? options.getBindiff() : null,
				"bindiff",
				"/opt/homebrew/bin/bindiff", // Apple-Silicon Homebrew default
				"/usr/local/bin/bindiff", // Intel Homebrew / manual installs
				"/opt/bindiff/bin/bindiff",
				"C:\\Program Files\\BinDiff\\bin\\bindiff.exe",
		};
		for (String cmd : candidates) {
			if (cmd != null && !cmd.isEmpty() && probe(cmd)) {
				return cmd;
			}
		}
		return null;
	}

	/** True if {@code cmd --help} runs and self-identifies as bindiff. */
	private static boolean probe(String cmd) {
		try {
			// bindiff --help exits non-zero (Google-flags CLI), so detect by output
			// content rather than exit code.
			ProcResult res = runProcess(PROBE_TIMEOUT_SEC, ExportProgress.NONE, cmd, "--help");
			return res.output.toLowerCase(Locale.ROOT).contains("bindiff");
		} catch (Exception ignored) {
			return false;
		}
	}

	/**
	 * Runs a subprocess, draining its (merged) output on a separate thread so a
	 * full pipe buffer can't block the child, and bounding it with a timeout so a
	 * wedged process is force-killed instead of hanging the caller forever. Polls
	 * {@code progress.cancelled()} while waiting so the user can abort a running
	 * bindiff mid-flight (the process is killed, not just ignored).
	 */
	private static ProcResult runProcess(long timeoutSeconds, ExportProgress progress, String... cmd)
			throws Exception {
		ExportProgress prog = progress != null ? progress : ExportProgress.NONE;
		Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
		StringBuilder out = new StringBuilder();
		Thread reader = new Thread(() -> {
			try (InputStream in = p.getInputStream()) {
				out.append(new String(in.readAllBytes(), StandardCharsets.UTF_8));
			} catch (IOException e) {
				throw new UncheckedIOException(e);
			}
		}, "bindiff-reader");
		reader.setDaemon(true);
		reader.start();

		// Poll in short slices: react to a cancel request or the deadline without
		// blocking for the whole timeout on a single waitFor.
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds);
		boolean exited = false;
		while (System.nanoTime() < deadline) {
			if (prog.cancelled()) {
				killAndDrain(p, reader);
				throw new Exporter.CancelledException();
			}
			if (p.waitFor(200, TimeUnit.MILLISECONDS)) {
				exited = true;
				break;
			}
		}
		if (!exited) {
			killAndDrain(p, reader);
			throw new IllegalStateException(
					"bindiff timed out after " + timeoutSeconds + "s: " + String.join(" ", cmd));
		}
		// The process has exited, so its stdout is at EOF and the reader returns
		// promptly; join without a timeout so out is fully written (and safely
		// published) before we read it - a bounded join could race the append.
		reader.join();
		return new ProcResult(p.exitValue(), out.toString());
	}

	/** Force-kills a process and waits briefly for it and its reader to settle. */
	private static void killAndDrain(Process p, Thread reader) throws InterruptedException {
		p.destroyForcibly();
		p.waitFor(5, TimeUnit.SECONDS);
		reader.join(1000); // already tearing down; don't block the caller further
	}

	private static void deleteRecursively(Path dir) {
		try (java.util.stream.Stream<Path> walk = Files.walk(dir)) {
			walk.sorted(Comparator.reverseOrder()).forEach(pth -> {
				try {
					Files.deleteIfExists(pth);
				} catch (IOException e) {
					LOG.debug("[BinExport] could not delete {}", pth, e);
				}
			});
		} catch (IOException e) {
			LOG.debug("[BinExport] could not clean up {}", dir, e);
		}
	}
}
