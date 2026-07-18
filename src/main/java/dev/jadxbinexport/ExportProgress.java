package dev.jadxbinexport;

/**
 * Progress sink for a running export so the GUI can show how far along the
 * (minutes-long on big apps) export is and let the user cancel it - mirroring
 * the IDA BinExport wait box and Ghidra's {@code TaskMonitor}. The CLI/library
 * path uses {@link #NONE}, keeping {@link Exporter} free of any Swing/GUI
 * dependency and headless-testable.
 *
 * <p>Implementations must be thread-safe: the exporter calls these from its own
 * worker thread, off the Swing EDT.
 */
public interface ExportProgress {

	/** No-op sink for non-interactive (CLI / library) exports. */
	ExportProgress NONE = new ExportProgress() {
		@Override
		public void stage(String label, int total) {
		}

		@Override
		public void update(int done, int total) {
		}

		@Override
		public boolean cancelled() {
			return false;
		}
	};

	/** Begins a stage; {@code total <= 0} means the work is indeterminate. */
	void stage(String label, int total);

	/** Reports progress within the current stage. */
	void update(int done, int total);

	/** True once the user asked to cancel; the exporter then aborts promptly. */
	boolean cancelled();
}
