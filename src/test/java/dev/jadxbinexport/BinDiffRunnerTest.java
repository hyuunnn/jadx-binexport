package dev.jadxbinexport;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;

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
		assumeTrue(BinDiffRunner.findBindiff(null) != null, "bindiff not found");

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

			File binDiff = BinDiffRunner.diff(jadx, otherBinExport, null);
			assertNotNull(binDiff, "runner returned no .BinDiff");
			assertTrue(binDiff.isFile(), "no .BinDiff produced");

			List<BinDiffResults.Match> matches = BinDiffResults.loadMatches(binDiff);
			assertFalse(matches.isEmpty(), "no matches from in-plugin diff");

			Map<String, MethodNode> index = BinDiffResults.methodIndex(jadx);
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
	 * Cancelling while bindiff is running must abort the diff (kill the process,
	 * throw {@link Exporter.CancelledException}) rather than run to completion -
	 * the fix for "Cancel only worked during the export phase".
	 */
	@Test
	void cancelDuringBindiffAborts(@TempDir Path tmp) throws Exception {
		assumeTrue(BinDiffRunner.findBindiff(null) != null, "bindiff not found");

		Path classes = compile(tmp);
		File otherBinExport = tmp.resolve("other.BinExport").toFile();
		exportBinExport(classes, tmp.resolve("jadx-b"), otherBinExport);

		// Reports false during the export stage, then true once bindiff starts, so
		// only the "Running BinDiff…" wait is cancelled (not the export).
		ExportProgress cancelAtBindiff = new ExportProgress() {
			private volatile boolean bindiffStarted;

			public void stage(String label, int total) {
				if (label.contains("BinDiff")) {
					bindiffStarted = true;
				}
			}

			public void update(int done, int total) {
			}

			public boolean cancelled() {
				return bindiffStarted;
			}
		};

		JadxArgs args = new JadxArgs();
		args.getInputFiles().add(classes.toFile());
		args.setOutDir(tmp.resolve("jadx-a").toFile());
		try (JadxDecompiler jadx = new JadxDecompiler(args)) {
			jadx.load();
			assertThrows(Exporter.CancelledException.class,
					() -> BinDiffRunner.diff(jadx, otherBinExport, null, cancelAtBindiff));
		}
		System.out.println("[runner] cancel during bindiff aborted the diff");
	}

	private static void exportBinExport(Path classes, Path jadxOut, File outFile) throws Exception {
		JadxArgs args = new JadxArgs();
		args.getInputFiles().add(classes.toFile());
		args.setOutDir(jadxOut.toFile());
		try (JadxDecompiler jadx = new JadxDecompiler(args)) {
			jadx.load();
			Exporter.runToFile(jadx, outFile);
		}
		assertTrue(outFile.isFile(), "other .BinExport not produced");
	}

	private static Path compile(Path tmp) throws IOException {
		Path srcDir = Files.createDirectories(tmp.resolve("src"));
		Path classesDir = Files.createDirectories(tmp.resolve("classes"));
		Path src = srcDir.resolve("Sample.java");
		Files.write(src, SAMPLE.getBytes());
		JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
		assertNotNull(compiler, "JDK (not JRE) required");
		assertEquals(0, compiler.run(null, null, null, "-d", classesDir.toString(), "-g", src.toString()),
				"javac failed");
		return classesDir;
	}
}
