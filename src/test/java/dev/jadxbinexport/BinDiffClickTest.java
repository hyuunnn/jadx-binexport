package dev.jadxbinexport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import java.awt.GraphicsEnvironment;
import java.awt.event.MouseEvent;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import javax.swing.JTable;
import javax.swing.SwingUtilities;
import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import jadx.api.JadxArgs;
import jadx.api.JadxDecompiler;
import jadx.core.dex.nodes.MethodNode;

/**
 * Drives the REAL results-table Swing code: builds the production table via
 * {@link BinDiffResultsPanel#buildNavigableTable}, dispatches a genuine
 * double-click {@link MouseEvent} to it, and asserts the navigation callback
 * fires with the exact {@link MethodNode} the row maps to (through the sorter's
 * view→model conversion). This exercises the same listener jadx-gui invokes.
 */
class BinDiffClickTest {

	private static final String SAMPLE = ""
			+ "public class Sample {\n"
			+ "  public int fib(int n){ if(n<2) return n; int a=0,b=1; for(int i=2;i<=n;i++){int c=a+b;a=b;b=c;} return b; }\n"
			+ "  public int use(int x){ return fib(x)+helper(x); }\n"
			+ "  public int helper(int x){ return x*3+1; }\n"
			+ "}\n";

	@Test
	void doubleClickNavigatesToTheRowsMethod(@TempDir Path tmp) throws Exception {
		assumeFalse(GraphicsEnvironment.isHeadless(), "no display; Swing table cannot be built");

		Path classes = compile(tmp);
		JadxArgs args = new JadxArgs();
		args.getInputFiles().add(classes.toFile());
		args.setOutDir(tmp.resolve("jadx-out").toFile());
		try (JadxDecompiler jadx = new JadxDecompiler(args)) {
			jadx.load();
			Map<String, MethodNode> index = BinDiffResults.methodIndex(jadx, ExportProgress.NONE);
			MethodNode fib = requireMethod(index, "fib");
			MethodNode helper = requireMethod(index, "helper");

			// Three matches with distinct similarities so the default sort
			// (ascending similarity) puts a known method's row first.
			List<BinDiffResults.Match> matches = new ArrayList<>();
			matches.add(match(index, fib, 0.42));    // lowest -> sorts to view row 0
			matches.add(match(index, helper, 0.99)); // highest
			matches.add(match(index, requireMethod(index, "use"), 0.77));

			AtomicReference<MethodNode> opened = new AtomicReference<>();
			AtomicReference<MethodNode> expectedRow0 = new AtomicReference<>();

			SwingUtilities.invokeAndWait(() -> {
				BinDiffResultsPanel.ResultsTableModel model =
						new BinDiffResultsPanel.ResultsTableModel(matches, index);
				JTable table = BinDiffResultsPanel.buildNavigableTable(model, opened::set);

				// View row 0 is the lowest-similarity match after sorting.
				expectedRow0.set(model.methodAt(table.convertRowIndexToModel(0)));
				table.setRowSelectionInterval(0, 0);

				// A real double-click on the table.
				MouseEvent dbl = new MouseEvent(table, MouseEvent.MOUSE_CLICKED,
						System.currentTimeMillis(), 0, 10, 10, 2, false);
				table.dispatchEvent(dbl);
			});

			assertNotNull(opened.get(), "double-click did not trigger navigation");
			assertSame(expectedRow0.get(), opened.get(),
					"navigated to the wrong method for the selected row");
			// Row 0 is the lowest-similarity match, which we set to fib.
			assertSame(fib, opened.get(), "sort order / navigation mismatch: expected fib at row 0");
			System.out.println("[click] double-click navigated to "
					+ opened.get().getMethodInfo().getRawFullId());
		}
	}

	private static BinDiffResults.Match match(Map<String, MethodNode> index, MethodNode m, double sim) {
		String id = m.getMethodInfo().getRawFullId();
		return new BinDiffResults.Match(id, id, sim, sim);
	}

	private static MethodNode requireMethod(Map<String, MethodNode> index, String shortName) {
		for (Map.Entry<String, MethodNode> e : index.entrySet()) {
			if (e.getKey().contains("." + shortName + "(")) {
				return e.getValue();
			}
		}
		throw new AssertionError("method not found in index: " + shortName);
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
