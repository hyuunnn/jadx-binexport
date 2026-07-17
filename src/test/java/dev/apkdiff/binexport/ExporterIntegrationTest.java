package dev.apkdiff.binexport;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

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

		// Meta
		assertNotNull(be.getMetaInformation());
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
		boolean anyBackEdge = be.getFlowGraphList().stream()
				.flatMap(fg -> fg.getEdgeList().stream())
				.anyMatch(BinExport2.FlowGraph.Edge::getIsBackEdge);
		System.out.println("[test] vertices=" + cg.getVertexCount()
				+ " edges=" + cg.getEdgeCount()
				+ " instructions=" + be.getInstructionCount()
				+ " basicBlocks=" + be.getBasicBlockCount()
				+ " flowGraphs=" + be.getFlowGraphCount()
				+ " mnemonics=" + be.getMnemonicCount()
				+ " backEdge=" + anyBackEdge);
	}

	private static Path compileSample(Path tmp) throws IOException {
		Path srcDir = Files.createDirectories(tmp.resolve("src"));
		Path classesDir = Files.createDirectories(tmp.resolve("classes"));
		Path src = srcDir.resolve("Sample.java");
		Files.write(src, SAMPLE.getBytes());

		JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
		assertNotNull(compiler, "JDK (not JRE) required to run this test");
		List<String> opts = Arrays.asList("-d", classesDir.toString(), "-g");
		int rc = compiler.run(null, null, null,
				concat(opts, src.toString()));
		assertTrue(rc == 0, "javac failed rc=" + rc);
		return classesDir;
	}

	private static String[] concat(List<String> opts, String last) {
		String[] arr = new String[opts.size() + 1];
		for (int i = 0; i < opts.size(); i++) {
			arr[i] = opts.get(i);
		}
		arr[opts.size()] = last;
		return arr;
	}
}
