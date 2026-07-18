package dev.jadxbinexport;

import java.io.File;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteDataSource;

import jadx.api.JadxDecompiler;
import jadx.core.dex.nodes.ClassNode;
import jadx.core.dex.nodes.MethodNode;

/**
 * Reads a BinDiff results database ({@code .BinDiff}, a SQLite file) and links
 * its matched function pairs back to methods in the current jadx session.
 *
 * <p>BinDiff stores each matched pair in a {@code function} table as
 * {@code (address1, name1, address2, name2, similarity, confidence, ...)}. The
 * {@code name1}/{@code name2} columns are exactly the {@code mangled_name} this
 * exporter wrote (jadx's {@code MethodInfo.getRawFullId()}), so a match can be
 * resolved to a {@link MethodNode} by name without relying on the synthetic
 * addresses. This is the data behind the navigable results table.
 */
public final class BinDiffResults {

	/** One matched function pair from the results DB. */
	public static final class Match {
		public final String name1;
		public final String name2;
		public final double similarity;
		public final double confidence;

		Match(String name1, String name2, double similarity, double confidence) {
			this.name1 = name1;
			this.name2 = name2;
			this.similarity = similarity;
			this.confidence = confidence;
		}
	}

	/** Primary/secondary file names recorded in the DB (for display). */
	public static final class Header {
		public final String primary;
		public final String secondary;

		Header(String primary, String secondary) {
			this.primary = primary;
			this.secondary = secondary;
		}
	}

	private BinDiffResults() {
	}

	/** Reads all matched function pairs from a {@code .BinDiff} database. */
	public static List<Match> loadMatches(File binDiff) throws Exception {
		List<Match> out = new ArrayList<>();
		try (Connection con = open(binDiff);
				Statement st = con.createStatement();
				ResultSet rs = st.executeQuery(
						"SELECT name1, name2, similarity, confidence FROM function")) {
			while (rs.next()) {
				out.add(new Match(
						rs.getString(1),
						rs.getString(2),
						rs.getDouble(3),
						rs.getDouble(4)));
			}
		}
		return out;
	}

	/** Reads the primary/secondary file names, or {@code null} if unavailable. */
	public static Header loadHeader(File binDiff) {
		try (Connection con = open(binDiff);
				Statement st = con.createStatement();
				ResultSet rs = st.executeQuery("SELECT filename FROM file ORDER BY id")) {
			String primary = rs.next() ? rs.getString(1) : "primary";
			String secondary = rs.next() ? rs.getString(1) : "secondary";
			return new Header(primary, secondary);
		} catch (Exception e) {
			return null;
		}
	}

	/**
	 * Builds a {@code mangledName -> MethodNode} index for the current session so
	 * a match name can be resolved to something {@code JadxGuiContext.open} can
	 * navigate to. Enumerates methods exactly like {@link Exporter} (full model
	 * via {@code getRoot().getClasses()} plus inner/inlined classes, including
	 * constructors) so the keys line up with the exported {@code mangled_name}s.
	 */
	public static Map<String, MethodNode> methodIndex(JadxDecompiler decompiler) {
		return methodIndex(decompiler, ExportProgress.NONE);
	}

	/**
	 * As {@link #methodIndex(JadxDecompiler)} but polls {@code progress} so the
	 * "Loading results…" class walk (slow on a big app) can be cancelled instead
	 * of only being discarded after it finishes.
	 */
	public static Map<String, MethodNode> methodIndex(JadxDecompiler decompiler, ExportProgress progress) {
		ExportProgress prog = ExportProgress.orNone(progress);
		Map<String, MethodNode> index = new HashMap<>();
		Set<ClassNode> visited = Collections.newSetFromMap(new IdentityHashMap<>());
		List<ClassNode> classes = decompiler.getRoot().getClasses();
		for (int i = 0; i < classes.size(); i++) {
			if ((i & 255) == 0 && prog.cancelled()) {
				throw new Exporter.CancelledException();
			}
			indexClass(classes.get(i), visited, index);
		}
		return index;
	}

	private static void indexClass(ClassNode cls, Set<ClassNode> visited, Map<String, MethodNode> index) {
		if (cls == null || !visited.add(cls)) {
			return;
		}
		for (MethodNode mth : cls.getMethods()) {
			index.putIfAbsent(mth.getMethodInfo().getRawFullId(), mth);
		}
		for (ClassNode inner : cls.getInnerClasses()) {
			indexClass(inner, visited, index);
		}
		for (ClassNode inlined : cls.getInlinedClasses()) {
			indexClass(inlined, visited, index);
		}
	}

	/**
	 * Picks the side of a match that belongs to the currently-open app and
	 * returns its method (the navigation target), or {@code null} if neither
	 * side is present in this session.
	 */
	public static MethodNode resolveLocal(Match m, Map<String, MethodNode> index) {
		MethodNode local = index.get(m.name1);
		if (local != null) {
			return local;
		}
		return index.get(m.name2);
	}

	/** The counterpart name in the OTHER version, given the local side. */
	public static String counterpart(Match m, Map<String, MethodNode> index) {
		return index.containsKey(m.name1) ? m.name2 : m.name1;
	}

	private static Connection open(File binDiff) throws Exception {
		if (!binDiff.isFile()) {
			throw new IllegalArgumentException("not a file: " + binDiff);
		}
		// SQLiteDataSource creates connections directly (no DriverManager), which
		// avoids the child-classloader SPI problem when running inside jadx.
		SQLiteConfig cfg = new SQLiteConfig();
		cfg.setReadOnly(true);
		SQLiteDataSource ds = new SQLiteDataSource(cfg);
		ds.setUrl("jdbc:sqlite:" + binDiff.getAbsolutePath());
		return ds.getConnection();
	}
}
