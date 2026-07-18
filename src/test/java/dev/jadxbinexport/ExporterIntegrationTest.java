package dev.jadxbinexport;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.google.security.zynamics.BinExport.BinExport2;

import jadx.api.JadxArgs;
import jadx.api.JadxDecompiler;

/**
 * End-to-end check: compile a small class, run it through jadx (which triggers
 * the exporter via the after-load pass), then re-parse the emitted BinExport2
 * and assert the graph structure is present and self-consistent.
 */
class ExporterIntegrationTest {

	private static final String SAMPLE = ""
			+ "public class Sample {\n"
			+ "  public int fib(int n) {\n"
			+ "    if (n < 2) return n;\n"
			+ "    int a = 0, b = 1;\n"
			+ "    for (int i = 2; i <= n; i++) { int c = a + b; a = b; b = c; }\n"
			+ "    return b;\n"
			+ "  }\n"
			+ "  public int caller(int x) { return fib(x) + helper(x); }\n"
			+ "  public int helper(int x) { return x * 2; }\n"
			+ "  public long fact(long n) { return n < 2 ? 1 : n * fact(n - 1); }\n"
			+ "}\n";

	@Test
	void exportsCallGraphAndFlowGraphs(@TempDir Path tmp) throws Exception {
		Path classesDir = compileSample(tmp);
		Path out = tmp.resolve("sample.BinExport");
		System.setProperty("binexport.output", out.toString());
		try {
			JadxArgs args = new JadxArgs();
			args.getInputFiles().add(classesDir.toFile());
			args.setOutDir(tmp.resolve("jadx-out").toFile());
			try (JadxDecompiler jadx = new JadxDecompiler(args)) {
				jadx.load(); // triggers BinExportPass (after-load) => writes the file
			}
		} finally {
			System.clearProperty("binexport.output");
		}

		assertTrue(Files.isRegularFile(out), "BinExport file was not produced");

		BinExport2 be = parse(out);

		// Meta - architecture carries the jadx version so a version-mismatch
		// diff is detectable in BinDiff's UI/results.
		assertNotNull(be.getMetaInformation());
		assertTrue(be.getMetaInformation().getArchitectureName().startsWith("dalvik-jadx-"),
				"architecture must embed the jadx version: "
						+ be.getMetaInformation().getArchitectureName());
		assertTrue(be.getModuleCount() >= 1, "expected at least one module (class)");

		// Call graph: <init>, fib, caller, helper => >= 4 vertices, sorted by address.
		BinExport2.CallGraph cg = be.getCallGraph();
		assertTrue(cg.getVertexCount() >= 4, "vertices: " + cg.getVertexCount());
		assertVerticesSorted(cg.getVertexList());
		assertTrue(cg.getEdgeCount() >= 1, "expected caller->fib/helper call edges");

		// Bodies
		assertTrue(be.getInstructionCount() > 0, "no instructions");
		assertTrue(be.getFlowGraphCount() >= 1, "no flow graphs");

		// Every flow graph must reference valid, existing basic blocks and its
		// entry must be one of its own blocks.
		for (BinExport2.FlowGraph fg : be.getFlowGraphList()) {
			assertTrue(fg.getBasicBlockIndexCount() >= 1);
			assertTrue(fg.getBasicBlockIndexList().contains(fg.getEntryBasicBlockIndex()),
					"entry block must belong to the flow graph");
			for (int bbIdx : fg.getBasicBlockIndexList()) {
				assertTrue(bbIdx >= 0 && bbIdx < be.getBasicBlockCount(), "bb index out of range");
			}
			for (BinExport2.FlowGraph.Edge e : fg.getEdgeList()) {
				assertTrue(e.getSourceBasicBlockIndex() < be.getBasicBlockCount());
				assertTrue(e.getTargetBasicBlockIndex() < be.getBasicBlockCount());
			}
		}

		// Basic block instruction ranges must point at real instructions.
		for (BinExport2.BasicBlock bb : be.getBasicBlockList()) {
			for (BinExport2.BasicBlock.IndexRange r : bb.getInstructionIndexList()) {
				int begin = r.getBeginIndex();
				int end = r.hasEndIndex() ? r.getEndIndex() : begin + 1;
				assertTrue(begin >= 0 && end <= be.getInstructionCount() && begin < end,
						"bad instruction range [" + begin + "," + end + ")");
			}
		}

		// The for-loop in fib() should surface at least one back edge somewhere.
		boolean anyBackEdge = anyFlowEdge(be, BinExport2.FlowGraph.Edge::getIsBackEdge);
		assertTrue(anyBackEdge, "expected a back edge from the loop in fib()");

		// The if-branches in fib()/fact() must be labeled from the IfNode's
		// then/else blocks (successor order alone is unreliable in jadx).
		assertTrue(anyFlowEdge(be, e -> e.getType() == BinExport2.FlowGraph.Edge.Type.CONDITION_TRUE)
						&& anyFlowEdge(be, e -> e.getType() == BinExport2.FlowGraph.Edge.Type.CONDITION_FALSE),
				"expected CONDITION_TRUE and CONDITION_FALSE edges");

		// fact() is self-recursive: the call graph must keep the self edge.
		boolean selfEdge = cg.getEdgeList().stream()
				.anyMatch(e -> e.getSourceVertexIndex() == e.getTargetVertexIndex());
		assertTrue(selfEdge, "expected a self edge for the recursive fact()");

		// Calls folded into operand trees still carry call_target.
		boolean anyCallTarget = be.getInstructionList().stream()
				.anyMatch(i -> i.getCallTargetCount() > 0);
		assertTrue(anyCallTarget, "expected at least one instruction with call_target");

		System.out.println("[test] vertices=" + cg.getVertexCount()
				+ " edges=" + cg.getEdgeCount()
				+ " instructions=" + be.getInstructionCount()
				+ " basicBlocks=" + be.getBasicBlockCount()
				+ " flowGraphs=" + be.getFlowGraphCount()
				+ " mnemonics=" + be.getMnemonicCount()
				+ " backEdge=" + anyBackEdge);
	}

	/**
	 * The GUI progress path: the exporter must report named stages with in-range
	 * counts, and a cancel request must abort promptly WITHOUT writing a file
	 * (the file is written only after all building, so an aborted run leaves
	 * nothing behind).
	 */
	@Test
	void reportsProgressAndHonorsCancellation(@TempDir Path tmp) throws Exception {
		Path classesDir = compileSample(tmp);
		JadxArgs args = new JadxArgs();
		args.getInputFiles().add(classesDir.toFile());
		args.setOutDir(tmp.resolve("jadx-out").toFile());
		System.setProperty("binexport.output", tmp.resolve("passthrough.BinExport").toString());
		try (JadxDecompiler jadx = new JadxDecompiler(args)) {
			jadx.load(); // after-load pass exports once with ExportProgress.NONE

			// 1. A recording sink sees both stages and valid update counts.
			List<String> stages = new ArrayList<>();
			boolean[] updated = {false};
			ExportProgress recorder = new ExportProgress() {
				public void stage(String label, int total) {
					stages.add(label);
				}

				public void update(int done, int total) {
					if (done >= 0 && done <= total && total > 0) {
						updated[0] = true;
					}
				}

				public boolean cancelled() {
					return false;
				}
			};
			Path recorded = tmp.resolve("recorded.BinExport");
			Exporter.runToFile(jadx, recorded.toFile(), recorder);
			assertTrue(Files.isRegularFile(recorded), "export should complete");
			assertTrue(stages.stream().anyMatch(s -> s.contains("Decompiling")),
					"expected a class-decompilation stage, got: " + stages);
			assertTrue(stages.stream().anyMatch(s -> s.contains("flow graph")),
					"expected a flow-graph stage, got: " + stages);
			assertTrue(updated[0], "update() should be called with in-range counts");

			// 2. A LATE cancel (arriving only once the export has reached its final
			//    "Writing file" stage, i.e. after collect/buildBodies/buildCallGraph)
			//    must still abort with CancelledException and write no file. This
			//    exercises the post-buildBodies cancel gate, not just a cancel at t=0.
			Path cancelled = tmp.resolve("cancelled.BinExport");
			boolean[] sawWriteStage = {false};
			ExportProgress cancelAtWrite = new ExportProgress() {
				private volatile boolean writing;

				public void stage(String label, int total) {
					if (label.contains("Writing")) {
						writing = true;
						sawWriteStage[0] = true;
					}
				}

				public boolean cancelled() {
					return writing;
				}
			};
			assertThrows(Exporter.CancelledException.class,
					() -> Exporter.runToFile(jadx, cancelled.toFile(), cancelAtWrite));
			assertTrue(sawWriteStage[0], "export should have reached the Writing stage before cancel");
			assertFalse(Files.exists(cancelled), "a late-cancelled export must not write a file");
		} finally {
			System.clearProperty("binexport.output");
		}
		System.out.println("[progress] stages reported + cancellation honored (no file written)");
	}

	/**
	 * strict mode: a failed export must abort the run (rethrow) so a CI pipeline
	 * sees a non-zero exit, while the default (lenient) contract swallows it so a
	 * failed export doesn't also fail jadx's decompilation.
	 */
	@Test
	void strictModeRethrowsExportFailure(@TempDir Path tmp) throws Exception {
		Path classesDir = compileSample(tmp);
		// Force the write to fail deterministically: the output path's parent is a
		// regular file, so mkdirs()/newOutputStream() cannot create it.
		Path blocker = Files.createFile(tmp.resolve("blocker"));
		Path badOut = blocker.resolve("out.BinExport");

		JadxArgs args = new JadxArgs();
		args.getInputFiles().add(classesDir.toFile());
		args.setOutDir(tmp.resolve("jadx-out").toFile());
		try (JadxDecompiler jadx = new JadxDecompiler(args)) {
			jadx.load(); // after-load pass exports to the default out dir (no bad path yet)

			System.setProperty("binexport.output", badOut.toString());
			try {
				// Lenient (strict off): the write failure is logged and swallowed.
				Exporter.runLogged(jadx, new BinExportOptions());

				// Strict on (via the legacy sysprop): the failure is rethrown.
				System.setProperty("binexport.strict", "true");
				assertThrows(RuntimeException.class,
						() -> Exporter.runLogged(jadx, new BinExportOptions()));
			} finally {
				System.clearProperty("binexport.output");
				System.clearProperty("binexport.strict");
			}
		}
		System.out.println("[strict] rethrew on failure when strict, swallowed when lenient");
	}

	/**
	 * The opt-in {@code jadx-binexport.imports}: external (framework/library) calls
	 * become IMPORTED call-graph vertices + edges. Default (off) must emit NONE
	 * (byte-for-byte the prior behavior); on must add them, keep the vertex list
	 * address-sorted, and target them from real edges.
	 */
	@Test
	void importedVerticesAreOptIn(@TempDir Path tmp) throws Exception {
		// f() calls two external java.lang methods -> two IMPORTED callees when on.
		String src = "public class Imp {\n"
				+ "  public int f(int n) { return Math.max(n, 0) + Integer.parseInt(\"7\"); }\n"
				+ "}\n";
		Path classesDir = compileClass(tmp, "Imp", src);

		JadxArgs args = new JadxArgs();
		args.getInputFiles().add(classesDir.toFile());
		args.setOutDir(tmp.resolve("jadx-out").toFile());
		try (JadxDecompiler jadx = new JadxDecompiler(args)) {
			jadx.load();

			// Default OFF: no IMPORTED vertices at all.
			Path off = tmp.resolve("off.BinExport");
			Exporter.runToFile(jadx, off.toFile());
			BinExport2 beOff = parse(off);
			assertTrue(beOff.getCallGraph().getVertexList().stream()
					.noneMatch(v -> v.getType() == BinExport2.CallGraph.Vertex.Type.IMPORTED),
					"imports off must emit no IMPORTED vertices");

			// ON: IMPORTED vertices present, an edge targets one, list stays sorted.
			// Driven through the REGISTERED plugin option (key + setter wiring),
			// not the legacy sysprop fallback - this is the primary documented
			// interface and would otherwise have zero coverage.
			Path on = tmp.resolve("on.BinExport");
			BinExportOptions optsOn = new BinExportOptions();
			optsOn.setOptions(Map.of(BinExportPlugin.PLUGIN_ID + ".imports", "true"));
			Exporter.runToFile(jadx, on.toFile(), ExportProgress.NONE, optsOn);
			BinExport2 beOn = parse(on);
			List<BinExport2.CallGraph.Vertex> vs = beOn.getCallGraph().getVertexList();

			assertVerticesSorted(vs);
			assertTrue(vs.stream().anyMatch(v -> v.getType() == BinExport2.CallGraph.Vertex.Type.IMPORTED),
					"imports on must emit IMPORTED vertices");
			assertTrue(vs.size() > beOff.getCallGraph().getVertexCount(),
					"imports on must add vertices");
			boolean anyExternalName = vs.stream()
					.filter(v -> v.getType() == BinExport2.CallGraph.Vertex.Type.IMPORTED)
					.anyMatch(v -> v.getMangledName().contains("Math") || v.getMangledName().contains("Integer"));
			assertTrue(anyExternalName, "an IMPORTED vertex should name an external callee");
			// Check the property itself (the target vertex IS imported), not the
			// list layout ("index above the first import") it happens to have.
			boolean edgeToImport = beOn.getCallGraph().getEdgeList().stream()
					.anyMatch(e -> vs.get(e.getTargetVertexIndex()).getType()
							== BinExport2.CallGraph.Vertex.Type.IMPORTED);
			assertTrue(edgeToImport, "some call edge must target an IMPORTED vertex");

			// The imports setting is a comparability axis, so it must be visible
			// in the meta (only when ON - default output stays byte-identical).
			assertTrue(beOn.getMetaInformation().getArchitectureName().endsWith("+imports"),
					"imports on must be recorded in architecture_name");
			assertFalse(beOff.getMetaInformation().getArchitectureName().contains("+imports"),
					"imports off must not change architecture_name");

			System.out.println("[imports] off=" + beOff.getCallGraph().getVertexCount()
					+ " vertices, on=" + vs.size() + " (with IMPORTED); edges target imports");
		}

		// Tri-state option semantics: an explicit =false must override the legacy
		// sysprop (a fallback, not an override); unset still falls back to it.
		System.setProperty("binexport.imports", "true");
		try {
			BinExportOptions explicitOff = new BinExportOptions();
			explicitOff.setOptions(Map.of(BinExportPlugin.PLUGIN_ID + ".imports", "false"));
			assertFalse(explicitOff.isImports(), "explicit =false must beat the sysprop");
			assertTrue(new BinExportOptions().isImports(), "unset must fall back to the sysprop");
		} finally {
			System.clearProperty("binexport.imports");
		}
	}

	private static BinExport2 parse(Path file) throws IOException {
		try (InputStream is = Files.newInputStream(file)) {
			return BinExport2.parseFrom(is);
		}
	}

	/** BinDiff hard rule: vertices sorted by strictly ascending address, all named. */
	private static void assertVerticesSorted(List<BinExport2.CallGraph.Vertex> vs) {
		long prev = -1;
		for (BinExport2.CallGraph.Vertex v : vs) {
			assertTrue(v.getAddress() > prev, "vertices must be sorted by ascending address");
			prev = v.getAddress();
			assertTrue(!v.getMangledName().isEmpty(), "vertex must have a name");
		}
	}

	private static boolean anyFlowEdge(BinExport2 be, Predicate<BinExport2.FlowGraph.Edge> pred) {
		return be.getFlowGraphList().stream()
				.flatMap(fg -> fg.getEdgeList().stream())
				.anyMatch(pred);
	}

	private static Path compileSample(Path tmp) throws IOException {
		return compileClass(tmp, "Sample", SAMPLE);
	}

	private static Path compileClass(Path tmp, String className, String source) throws IOException {
		Path srcDir = Files.createDirectories(tmp.resolve("src"));
		Path classesDir = Files.createDirectories(tmp.resolve("classes"));
		Path src = srcDir.resolve(className + ".java");
		Files.write(src, source.getBytes());

		JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
		assertNotNull(compiler, "JDK (not JRE) required to run this test");
		int rc = compiler.run(null, null, null, "-d", classesDir.toString(), "-g", src.toString());
		assertTrue(rc == 0, "javac failed rc=" + rc);
		return classesDir;
	}
}
