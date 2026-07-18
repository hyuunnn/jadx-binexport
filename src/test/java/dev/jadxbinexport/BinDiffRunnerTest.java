package dev.jadxbinexport;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import jadx.api.JadxArgs;
import jadx.api.JadxDecompiler;
import jadx.core.dex.nodes.MethodNode;

/**
 * Exercises the in-GUI "diff against a .BinExport" flow minus Swing: open app A
 * in jadx, point at B's {@code .BinExport}, and let {@link BinDiffRunner} export
 * A + run bindiff + produce a {@code .BinDiff}; then verify the results load and
 * resolve to methods of the open app. Requires bindiff; skipped otherwise.
 */
class BinDiffRunnerTest {

	private static final String SAMPLE = ""
			+ "public class Sample {\n"
			+ "  public int fib(int n){ if(n<2) return n; int a=0,b=1; for(int i=2;i<=n;i++){int c=a+b;a=b;b=c;} return b; }\n"
			+ "  public int use(int x){ return fib(x)+helper(x); }\n"
			+ "  public int helper(int x){ return x*3+1; }\n"
			+ "}\n";

	@Test
	void diffsCurrentAppAgainstAnotherBinExport(@TempDir Path tmp) throws Exception {
		assumeTrue(BinDiffRunner.findBindiff(null, ExportProgress.NONE) != null, "bindiff not found");

		Path classes = compile(tmp);

		// "Other version" B: produce its .BinExport up front.
		File otherBinExport = tmp.resolve("other.BinExport").toFile();
		exportBinExport(classes, tmp.resolve("jadx-b"), otherBinExport);

		// "Current app" A open in jadx; run the in-plugin diff against B.
		JadxArgs args = new JadxArgs();
		args.getInputFiles().add(classes.toFile());
		args.setOutDir(tmp.resolve("jadx-a").toFile());
		try (JadxDecompiler jadx = new JadxDecompiler(args)) {
			jadx.load();

			File binDiff = BinDiffRunner.diff(jadx, otherBinExport, null, ExportProgress.NONE);
			assertNotNull(binDiff, "runner returned no .BinDiff");
			assertTrue(binDiff.isFile(), "no .BinDiff produced");

			List<BinDiffResults.Match> matches = BinDiffResults.loadMatches(binDiff, ExportProgress.NONE);
			assertFalse(matches.isEmpty(), "no matches from in-plugin diff");

			Map<String, MethodNode> index = BinDiffResults.methodIndex(jadx, ExportProgress.NONE);
			int resolved = 0;
			for (BinDiffResults.Match m : matches) {
				if (BinDiffResults.resolveLocal(m, index) != null) {
					resolved++;
				}
			}
			assertTrue(resolved > 0, "no match resolved to a method of the open app");
			System.out.println("[runner] in-plugin diff produced " + matches.size()
					+ " matches, " + resolved + " navigable in the open app");
		}
	}

	/**
	 * Directly exercises the runProcess cancel path against a genuinely long-running
	 * process (not a sub-second bindiff): a cancel must kill it promptly and throw,
	 * rather than wait out the full runtime. Deterministic; no bindiff needed.
	 */
	@Test
	void runProcessKillsRunningProcessOnCancel() throws Exception {
		assumeTrue(!System.getProperty("os.name").toLowerCase().startsWith("win"), "needs POSIX sleep");

		ExportProgress alwaysCancel = new ExportProgress() {
			public boolean cancelled() {
				return true;
			}
		};
		long startNanos = System.nanoTime();
		assertThrows(Exporter.CancelledException.class,
				() -> BinDiffRunner.runProcess(60, alwaysCancel, "sleep", "30"));
		long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000;
		assertTrue(elapsedMs < 3000, "cancel should kill the process promptly, took " + elapsedMs + "ms");
		System.out.println("[runner] runProcess cancelled a running process in " + elapsedMs + "ms");
	}

	private static void exportBinExport(Path classes, Path jadxOut, File outFile) throws Exception {
		JadxArgs args = new JadxArgs();
		args.getInputFiles().add(classes.toFile());
		args.setOutDir(jadxOut.toFile());
		try (JadxDecompiler jadx = new JadxDecompiler(args)) {
			jadx.load();
			Exporter.runToFile(jadx, outFile, ExportProgress.NONE, null);
		}
		assertTrue(outFile.isFile(), "other .BinExport not produced");
	}

	private static Path compile(Path tmp) throws IOException {
		return TestCompiler.compile(tmp, "Sample", SAMPLE);
	}
}
