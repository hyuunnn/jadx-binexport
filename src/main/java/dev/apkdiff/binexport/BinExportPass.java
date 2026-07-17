package dev.apkdiff.binexport;

import java.util.Collections;
import java.util.List;

import jadx.api.JadxDecompiler;
import jadx.api.plugins.pass.JadxPassInfo;
import jadx.api.plugins.pass.types.JadxAfterLoadPass;

/**
 * Runs after all inputs are loaded (but before jadx saves sources) and drives
 * the BinExport export. This pass forces decompilation of every class itself so
 * that control flow graphs are available for each method.
 */
public class BinExportPass implements JadxAfterLoadPass {

	@Override
	public JadxPassInfo getInfo() {
		return new JadxPassInfo() {
			@Override
			public String getName() {
				return "BinExportPass";
			}

			@Override
			public String getDescription() {
				return "Export decompiler analysis to BinExport2 protobuf";
			}

			@Override
			public List<String> runAfter() {
				return Collections.emptyList();
			}

			@Override
			public List<String> runBefore() {
				return Collections.emptyList();
			}
		};
	}

	@Override
	public void init(JadxDecompiler decompiler) {
		try {
			Exporter.run(decompiler);
		} catch (Throwable t) {
			System.err.println("[BinExport] export failed: " + t);
			t.printStackTrace();
		}
	}
}
