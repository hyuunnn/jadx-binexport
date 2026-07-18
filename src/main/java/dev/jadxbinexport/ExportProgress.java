package dev.jadxbinexport;

/**
 * Progress sink for a running export so the GUI can show how far along the
 * (minutes-long on big apps) export is and let the user cancel it - mirroring
 * the IDA BinExport wait box and Ghidra's {@code TaskMonitor}. The CLI/library
 * path uses {@link #NONE}, keeping {@link Exporter} free of any Swing/GUI
 * dependency and headless-testable.
 *
 * <p>All methods have no-op defaults so a caller (or test double) overrides only
 * what it cares about. Implementations must be thread-safe: the exporter calls
 * these from its own worker thread, off the Swing EDT.
 */
public interface ExportProgress {

	/** No-op sink for non-interactive (CLI / library) exports. */
	ExportProgress NONE = new ExportProgress() {
	};

	/** Coerces a possibly-null sink to {@link #NONE} so callees never null-check. */
	static ExportProgress orNone(ExportProgress p) {
		return p != null ? p : NONE;
	}

	/** Begins a stage; {@code total <= 0} means the work is indeterminate. */
	default void stage(String label, int total) {
	}

	/** Reports progress within the current stage. */
	default void update(int done, int total) {
	}

	/** True once the user asked to cancel; the exporter then aborts promptly. */
	default boolean cancelled() {
		return false;
	}

	/**
	 * A non-fatal advisory the work wants the user to see (e.g. the two sides of
	 * a diff were exported with different {@code imports} settings). The work
	 * continues; sinks that can reach the user should surface it - a log-only
	 * warning is invisible in the GUI.
	 */
	default void warn(String message) {
	}
}
