package dev.apkdiff.binexport;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import com.google.security.zynamics.BinExport.BinExport2;

import jadx.api.JadxArgs;
import jadx.api.JadxDecompiler;

/**
 * Opt-in smoke test against a real APK (DEX input path):
 *
 * <pre>./gradlew test -Pbinexport.smoke.apk=/path/to/app.apk</pre>
 *
 * The unit-scale invariants live in {@link ExporterIntegrationTest}; this run
 * checks the same structural rules hold at real-app scale on jadx's dex-input
 * pipeline (multidex, obfuscated names, dex-only constructs).
 */
class RealApkSmokeTest {

	@Test
	@EnabledIfSystemProperty(named = "binexport.smoke.apk", matches = ".+")
	void exportsRealApk(@TempDir Path tmp) throws Exception {
		File apk = new File(System.getProperty("binexport.smoke.apk"));
		assertTrue(apk.isFile(), "APK not found: " + apk);

		Path out = tmp.resolve("smoke.BinExport");
		System.setProperty("binexport.output", out.toString());
		try {
			JadxArgs args = new JadxArgs();
			args.getInputFiles().add(apk);
			args.setOutDir(tmp.resolve("jadx-out").toFile());
			try (JadxDecompiler jadx = new JadxDecompiler(args)) {
				jadx.load(); // triggers the after-load pass => writes the file
			}
		} finally {
			System.clearProperty("binexport.output");
		}

		assertTrue(Files.isRegularFile(out), "BinExport file was not produced");
		BinExport2 be;
		try (InputStream is = Files.newInputStream(out)) {
			be = BinExport2.parseFrom(is);
		}

		BinExport2.CallGraph cg = be.getCallGraph();
		assertTrue(cg.getVertexCount() > 0, "no call-graph vertices");
		assertTrue(be.getInstructionCount() > 0, "no instructions");
		assertTrue(be.getFlowGraphCount() > 0, "no flow graphs");
		assertTrue(cg.getEdgeCount() > 0, "no call edges");

		long prev = -1;
		for (BinExport2.CallGraph.Vertex v : cg.getVertexList()) {
			assertTrue(v.getAddress() > prev, "vertices must be sorted by ascending address");
			prev = v.getAddress();
		}
		for (BinExport2.CallGraph.Edge e : cg.getEdgeList()) {
			assertTrue(e.getSourceVertexIndex() >= 0 && e.getSourceVertexIndex() < cg.getVertexCount());
			assertTrue(e.getTargetVertexIndex() >= 0 && e.getTargetVertexIndex() < cg.getVertexCount());
		}
		for (BinExport2.FlowGraph fg : be.getFlowGraphList()) {
			assertTrue(fg.getBasicBlockIndexList().contains(fg.getEntryBasicBlockIndex()),
					"entry block must belong to the flow graph");
			for (int bbIdx : fg.getBasicBlockIndexList()) {
				assertTrue(bbIdx >= 0 && bbIdx < be.getBasicBlockCount(), "bb index out of range");
			}
		}
		for (BinExport2.BasicBlock bb : be.getBasicBlockList()) {
			for (BinExport2.BasicBlock.IndexRange r : bb.getInstructionIndexList()) {
				int begin = r.getBeginIndex();
				int end = r.hasEndIndex() ? r.getEndIndex() : begin + 1;
				assertTrue(begin >= 0 && end <= be.getInstructionCount() && begin < end,
						"bad instruction range [" + begin + "," + end + ")");
			}
		}
		for (BinExport2.Instruction insn : be.getInstructionList()) {
			assertTrue(insn.getMnemonicIndex() >= 0 && insn.getMnemonicIndex() < be.getMnemonicCount(),
					"mnemonic index out of range");
		}

		System.out.println("[smoke] apk=" + apk.getName()
				+ " vertices=" + cg.getVertexCount()
				+ " callEdges=" + cg.getEdgeCount()
				+ " instructions=" + be.getInstructionCount()
				+ " basicBlocks=" + be.getBasicBlockCount()
				+ " flowGraphs=" + be.getFlowGraphCount()
				+ " mnemonics=" + be.getMnemonicCount()
				+ " modules=" + be.getModuleCount()
				+ " size=" + Files.size(out));
	}
}
