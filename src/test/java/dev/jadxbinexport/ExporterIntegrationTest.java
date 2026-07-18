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

		BinExport2 be;
		try (InputStream is = Files.newInputStream(out)) {
			be = BinExport2.parseFrom(is);
		}

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
		long prev = -1;
		for (BinExport2.CallGraph.Vertex v : cg.getVertexList()) {
			assertTrue(v.getAddress() > prev, "vertices must be sorted by ascending address");
			prev = v.getAddress();
			assertTrue(!v.getMangledName().isEmpty(), "vertex must have a name");
		}
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

			// 2. A cancel request aborts with CancelledException and writes nothing.
			Path cancelled = tmp.resolve("cancelled.BinExport");
			ExportProgress cancelling = new ExportProgress() {
				public void stage(String label, int total) {
				}

				public void update(int done, int total) {
				}

				public boolean cancelled() {
					return true;
				}
			};
			assertThrows(Exporter.CancelledException.class,
					() -> Exporter.runToFile(jadx, cancelled.toFile(), cancelling));
			assertFalse(Files.exists(cancelled), "a cancelled export must not write a file");
		} finally {
			System.clearProperty("binexport.output");
		}
		System.out.println("[progress] stages reported + cancellation honored (no file written)");
	}

	private static boolean anyFlowEdge(BinExport2 be, Predicate<BinExport2.FlowGraph.Edge> pred) {
		return be.getFlowGraphList().stream()
				.flatMap(fg -> fg.getEdgeList().stream())
				.anyMatch(pred);
	}

	private static Path compileSample(Path tmp) throws IOException {
		Path srcDir = Files.createDirectories(tmp.resolve("src"));
		Path classesDir = Files.createDirectories(tmp.resolve("classes"));
		Path src = srcDir.resolve("Sample.java");
		Files.write(src, SAMPLE.getBytes());

		JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
		assertNotNull(compiler, "JDK (not JRE) required to run this test");
		int rc = compiler.run(null, null, null, "-d", classesDir.toString(), "-g", src.toString());
		assertTrue(rc == 0, "javac failed rc=" + rc);
		return classesDir;
	}
}
