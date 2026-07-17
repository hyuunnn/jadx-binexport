package dev.apkdiff.binexport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

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
 * End-to-end check of the "load BinDiff results, navigate to a function" path,
 * minus the Swing shell: compile a sample, export it, run BinDiff on two copies,
 * read the results back, and resolve every match to a real {@link MethodNode}
 * (the exact node the GUI would hand to {@code JadxGuiContext.open}).
 *
 * <p>Requires the {@code bindiff} CLI on PATH; skipped otherwise.
 */
class BinDiffNavigationTest {

	private static final String SAMPLE = ""
			+ "public class Sample {\n"
			+ "  public int fib(int n){ if(n<2) return n; int a=0,b=1; for(int i=2;i<=n;i++){int c=a+b;a=b;b=c;} return b; }\n"
			+ "  public int use(int x){ return fib(x)+helper(x); }\n"
			+ "  public int helper(int x){ return x*3+1; }\n"
			+ "}\n";

	@Test
	void resolvesEveryMatchToAMethodNode(@TempDir Path tmp) throws Exception {
		assumeTrue(bindiffCmd() != null, "bindiff CLI not found");

		Path classes = compile(tmp);
		Path be = tmp.resolve("sample.BinExport");
		exportBinExport(classes, tmp.resolve("jadx-out"), be);

		Path binDiff = tmp.resolve("out").resolve("sample_vs_sample.BinDiff");
		runBinDiff(be, be, tmp.resolve("out"));
		assertTrue(Files.isRegularFile(binDiff), "bindiff produced no .BinDiff: " + binDiff);

		// Read results and build a navigation index from a fresh jadx session on
		// the same input - exactly what the GUI action does.
		List<BinDiffResults.Match> matches = BinDiffResults.loadMatches(binDiff.toFile());
		assertFalse(matches.isEmpty(), "no matches in results DB");

		JadxArgs args = new JadxArgs();
		args.getInputFiles().add(classes.toFile());
		args.setOutDir(tmp.resolve("jadx-out2").toFile());
		try (JadxDecompiler jadx = new JadxDecompiler(args)) {
			jadx.load();
			Map<String, MethodNode> index = BinDiffResults.methodIndex(jadx);
			assertFalse(index.isEmpty(), "empty method index");

			int resolved = 0;
			for (BinDiffResults.Match m : matches) {
				MethodNode target = BinDiffResults.resolveLocal(m, index);
				assertNotNull(target, "match did not resolve to a method: " + m.name1 + " / " + m.name2);
				// The navigation target's id must equal the side that resolved.
				String id = target.getMethodInfo().getRawFullId();
				assertTrue(id.equals(m.name1) || id.equals(m.name2),
						"resolved node id " + id + " matches neither side of " + m.name1 + "/" + m.name2);
				resolved++;
			}
			assertEquals(matches.size(), resolved, "some matches did not resolve");
			System.out.println("[nav] matches=" + matches.size() + " all resolved to MethodNode; index=" + index.size());
		}
	}

	private static void exportBinExport(Path classes, Path jadxOut, Path outFile) throws Exception {
		System.setProperty("binexport.output", outFile.toString());
		try {
			JadxArgs args = new JadxArgs();
			args.getInputFiles().add(classes.toFile());
			args.setOutDir(jadxOut.toFile());
			try (JadxDecompiler jadx = new JadxDecompiler(args)) {
				jadx.load(); // after-load pass writes the .BinExport
			}
		} finally {
			System.clearProperty("binexport.output");
		}
		assertTrue(Files.isRegularFile(outFile), "export produced no file");
	}

	private static void runBinDiff(Path primary, Path secondary, Path outDir) throws Exception {
		Files.createDirectories(outDir);
		Process p = new ProcessBuilder(bindiffCmd(),
				primary.toString(), secondary.toString(), "--output_dir", outDir.toString())
				.redirectErrorStream(true).start();
		p.getInputStream().readAllBytes();
		assertEquals(0, p.waitFor(), "bindiff exited non-zero");
	}

	/** Locates bindiff via -Dbinexport.bindiff, then PATH, then common paths. */
	private static String bindiffCmd() {
		String[] candidates = {
				System.getProperty("binexport.bindiff"),
				"bindiff",
				"/usr/local/bin/bindiff",
				"/opt/bindiff/bin/bindiff",
		};
		for (String cmd : candidates) {
			if (cmd == null) {
				continue;
			}
			try {
				// bindiff --help exits non-zero (Google-flags CLI), so detect by
				// output content rather than exit code.
				Process p = new ProcessBuilder(cmd, "--help").redirectErrorStream(true).start();
				String out = new String(p.getInputStream().readAllBytes());
				p.waitFor();
				if (out.toLowerCase(java.util.Locale.ROOT).contains("bindiff")) {
					return cmd;
				}
			} catch (Exception ignored) {
				// try next candidate
			}
		}
		return null;
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
