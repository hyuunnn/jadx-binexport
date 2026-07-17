package dev.jadxbinexport;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.protobuf.ByteString;
import com.google.security.zynamics.BinExport.BinExport2;
import com.google.security.zynamics.BinExport.BinExport2.Builder;

import jadx.api.JadxArgs;
import jadx.api.JadxDecompiler;
import jadx.api.plugins.utils.CommonFileUtils;
import jadx.core.Jadx;
import jadx.core.dex.info.MethodInfo;
import jadx.core.dex.instructions.BaseInvokeNode;
import jadx.core.dex.instructions.IfNode;
import jadx.core.dex.instructions.InsnType;
import jadx.core.dex.instructions.args.InsnArg;
import jadx.core.dex.instructions.args.LiteralArg;
import jadx.core.dex.instructions.args.RegisterArg;
import jadx.core.dex.nodes.BlockNode;
import jadx.core.dex.nodes.ClassNode;
import jadx.core.dex.nodes.InsnNode;
import jadx.core.dex.nodes.MethodNode;

/**
 * Maps a fully-analyzed jadx model onto a {@link BinExport2} protobuf.
 *
 * <p>Because Dalvik has no linear address space, every method is assigned a
 * stable synthetic 64-bit base address derived from a deterministic ordering of
 * method signatures. Instruction addresses are {@code base + sequenceIndex}.
 * Addresses only need to be unique and consistent within a single file - BinDiff
 * matches structurally, not by absolute address - so this is sufficient and keeps
 * the same method at the same address across rebuilds of the same input.
 */
public final class Exporter {

	private static final Logger LOG = LoggerFactory.getLogger(Exporter.class);

	/** Room for up to 2^20 (~1M) instructions per method before addresses collide. */
	private static final long METHOD_ADDR_SHIFT = 20;
	private static final long METHOD_ADDR_STRIDE = 1L << METHOD_ADDR_SHIFT;

	// BinDiff silently discards function bodies above these (flow_graph.cc
	// kMaxFunctionInstructions/kMaxFunctionBasicBlocks); we export them anyway
	// (other consumers may cope) but count and surface it.
	private static final int BINDIFF_MAX_INSNS = 10_000;
	private static final int BINDIFF_MAX_BLOCKS = 5_000;

	private static final String NOP = "nop";
	private static final ByteString NOP_BYTES = ByteString.copyFromUtf8(NOP);

	/** InsnType is a small fixed enum; derive each mnemonic string exactly once. */
	private static final Map<InsnType, String> MNEMONIC_BY_TYPE = buildMnemonicTable();

	private final Builder be = BinExport2.newBuilder();

	private final BinExportOptions options;
	private File explicitOutput;

	private final List<MethodNode> methods = new ArrayList<>();
	// MethodInfo.getRawFullId() rebuilds its string on every call and is used in
	// sorting, index lookups and naming, so cache it per method.
	private final Map<MethodNode, String> rawIdCache = new IdentityHashMap<>();
	private final Map<String, Integer> methodIndexByRawId = new HashMap<>();
	// Top-level classes whose forceProcess failed: their IR may be partially
	// transformed, so their methods are exported as call-graph vertices only.
	private final Set<ClassNode> processFailed = Collections.newSetFromMap(new IdentityHashMap<>());
	// Resolved once, shared by the mnemonic, body and call-graph passes.
	private final List<List<BlockNode>> blocksByMethod = new ArrayList<>();
	// Callees per method, collected while emitting instructions (deduped,
	// wrapped invokes included, self-recursion kept).
	private final List<Set<Integer>> calleesByMethod = new ArrayList<>();

	// jadx interns MethodInfo, so callee resolution and callee symbol operands
	// are cached by identity - getRawFullId() rebuilds its string per call, and
	// external (framework) callees would otherwise rebuild it on every invoke.
	private static final int NOT_IN_APP = -1;
	private final Map<MethodInfo, Integer> calleeIdxCache = new IdentityHashMap<>();
	private final Map<MethodInfo, Integer> calleeOperandIdx = new IdentityHashMap<>();
	private final Map<MethodInfo, String> calleeRawIdCache = new IdentityHashMap<>();

	private int binDiffOversized = 0;

	// Operand de-dup keyed by the primitive that fully determines the operand
	// (every operand here is a single expression), so the hit path allocates no
	// throwaway proto messages. Same result as BinExport's proto-keyed scheme.
	private final Map<Long, Integer> immOperandIdx = new HashMap<>();
	private final Map<Integer, Integer> regOperandIdx = new HashMap<>();
	private final Map<String, Integer> symOperandIdx = new HashMap<>();
	private final Map<String, Integer> mnemonicIdx = new HashMap<>();
	private final Map<String, Integer> moduleIdx = new HashMap<>();

	private Exporter(BinExportOptions options) {
		// A fresh options instance resolves everything from the legacy system
		// properties, so library callers without registered options still work.
		this.options = options != null ? options : new BinExportOptions();
	}

	/** Entry point: build and write the .BinExport file, returning its path. */
	public static File run(JadxDecompiler decompiler) {
		return run(decompiler, null);
	}

	public static File run(JadxDecompiler decompiler, BinExportOptions options) {
		return new Exporter(options).export(decompiler);
	}

	/** Exports to an explicit file, bypassing option/sysprop path resolution. */
	public static File runToFile(JadxDecompiler decompiler, File out) {
		Exporter e = new Exporter(null);
		e.explicitOutput = out;
		return e.export(decompiler);
	}

	/** Shared error contract for the CLI pass and the GUI action. */
	public static void runLogged(JadxDecompiler decompiler, BinExportOptions options) {
		try {
			run(decompiler, options);
		} catch (Throwable t) {
			LOG.error("[BinExport] export failed", t);
		}
	}

	private File export(JadxDecompiler decompiler) {
		// 1. Force decompilation and collect every method. getRoot().getClasses()
		// enumerates the full model; decompiler.getClasses() would silently drop
		// classes flagged DONT_GENERATE (deduplicated multidex entries, synthetic
		// holders), losing their call-graph vertices.
		Set<ClassNode> visited = Collections.newSetFromMap(new IdentityHashMap<>());
		for (ClassNode cls : decompiler.getRoot().getClasses()) {
			collect(cls, visited);
		}

		// 2. Deterministic method ordering => stable synthetic addresses.
		methods.sort(Comparator.comparing(this::rawId));
		for (int i = 0; i < methods.size(); i++) {
			String id = rawId(methods.get(i));
			Integer prev = methodIndexByRawId.putIfAbsent(id, i);
			if (prev != null) {
				LOG.warn("[BinExport] duplicate method id '{}'; calls resolve to its first copy", id);
			}
		}
		resolveBodies();

		// 3. Global tables and graphs (order matters for index bookkeeping).
		buildMnemonics();
		buildBodies();
		buildCallGraph();
		buildMeta(decompiler.getArgs());

		// 4. Serialize.
		File out = explicitOutput != null ? explicitOutput : resolveOutputFile(decompiler.getArgs());
		File parent = out.getParentFile();
		if (parent != null) {
			parent.mkdirs();
		}
		if (out.exists()) {
			LOG.warn("[BinExport] overwriting existing {}", out.getAbsolutePath());
		}
		try (OutputStream os = Files.newOutputStream(out.toPath())) {
			be.build().writeTo(os);
		} catch (IOException e) {
			throw new RuntimeException("BinExport write failed: " + out, e);
		}
		if (!processFailed.isEmpty()) {
			LOG.warn("[BinExport] {} top-level class(es) failed to process; their methods were exported without bodies",
					processFailed.size());
		}
		if (binDiffOversized > 0) {
			LOG.info("[BinExport] {} method(s) exceed BinDiff's per-function limits (>={} instructions or >={} blocks);"
					+ " BinDiff will match them by name/call-graph only",
					binDiffOversized, BINDIFF_MAX_INSNS, BINDIFF_MAX_BLOCKS);
		}
		LOG.info("[BinExport] wrote {} methods, {} instructions to {}",
				methods.size(), be.getInstructionCount(), out.getAbsolutePath());
		return out;
	}

	private void collect(ClassNode cls, Set<ClassNode> visited) {
		if (cls == null || !visited.add(cls)) {
			return;
		}
		try {
			// Run the decompile passes (block splitting, SSA, dominators, region
			// making) WITHOUT code generation. Plain cls.decompile() would unload
			// the class right after codegen, discarding the basic blocks we need.
			cls.root().getProcessClasses().forceProcess(cls);
		} catch (Throwable t) {
			ClassNode top = cls.getTopParentClass();
			if (processFailed.add(top)) {
				LOG.warn("[BinExport] process failed for {}; exporting its methods without bodies", top, t);
			}
		}
		methods.addAll(cls.getMethods());
		for (ClassNode inner : cls.getInnerClasses()) {
			collect(inner, visited);
		}
		for (ClassNode inlined : cls.getInlinedClasses()) {
			collect(inlined, visited);
		}
	}

	// --- Body resolution -----------------------------------------------------

	/**
	 * Resolves, once, the block list each method exports with. Empty for no-code
	 * methods, methods of classes whose processing failed (partially transformed
	 * IR would export misleading bodies), and methods whose emitted instruction
	 * count would spill past the per-method address stride (addresses would stop
	 * being unique within the file).
	 */
	private void resolveBodies() {
		for (MethodNode mth : methods) {
			blocksByMethod.add(resolveBlocks(mth));
			calleesByMethod.add(new LinkedHashSet<>());
		}
	}

	private List<BlockNode> resolveBlocks(MethodNode mth) {
		if (processFailed.contains(mth.getParentClass().getTopParentClass())) {
			return Collections.emptyList();
		}
		List<BlockNode> blocks = rawBlocks(mth);
		long emitted = 0;
		for (BlockNode b : blocks) {
			emitted += Math.max(1, b.getInstructions().size());
		}
		if (emitted >= METHOD_ADDR_STRIDE) {
			LOG.warn("[BinExport] method {} emits {} instructions, exceeding the 2^{} per-method address space;"
					+ " exported without body", rawId(mth), emitted, METHOD_ADDR_SHIFT);
			return Collections.emptyList();
		}
		if (emitted >= BINDIFF_MAX_INSNS || blocks.size() >= BINDIFF_MAX_BLOCKS) {
			binDiffOversized++;
		}
		BlockNode enter = mth.getEnterBlock();
		if (enter != null && blocks.get(0) != enter && blocks.contains(enter)) {
			// BinDiff associates a flow graph with its call-graph vertex by the
			// entry block's first-instruction address, which must equal the vertex
			// address (this method's base) or BinDiff aborts the whole diff. jadx
			// normally keeps the enter block at index 0, but e.g. a loop
			// pre-header insertion can append a fresh enter block at the END of
			// the list - move it to the front before emission order assigns
			// addresses.
			List<BlockNode> reordered = new ArrayList<>(blocks.size());
			reordered.add(enter);
			for (BlockNode b : blocks) {
				if (b != enter) {
					reordered.add(b);
				}
			}
			blocks = reordered;
		}
		return blocks;
	}

	/** Whatever blocks the jadx model has, without the body-export filters. */
	private static List<BlockNode> rawBlocks(MethodNode mth) {
		try {
			if (mth.isNoCode()) {
				return Collections.emptyList();
			}
			List<BlockNode> blocks = mth.getBasicBlocks();
			return blocks == null ? Collections.emptyList() : blocks;
		} catch (Throwable t) {
			return Collections.emptyList();
		}
	}

	// --- Mnemonics -----------------------------------------------------------

	private void buildMnemonics() {
		Map<String, Integer> hist = new HashMap<>();
		for (List<BlockNode> blocks : blocksByMethod) {
			for (BlockNode b : blocks) {
				List<InsnNode> insns = b.getInstructions();
				if (insns.isEmpty()) {
					hist.merge(NOP, 1, Integer::sum);
				} else {
					for (InsnNode insn : insns) {
						hist.merge(mnemonicOf(insn), 1, Integer::sum);
					}
				}
			}
		}
		if (hist.isEmpty()) {
			hist.put(NOP, 1);
		}
		// Most frequent mnemonic gets index 0 (the proto default), like BinExport.
		List<Map.Entry<String, Integer>> list = new ArrayList<>(hist.entrySet());
		list.sort(Comparator
				.comparingInt((Map.Entry<String, Integer> e) -> e.getValue()).reversed()
				.thenComparing(Map.Entry::getKey));
		int id = 0;
		for (Map.Entry<String, Integer> e : list) {
			be.addMnemonicBuilder().setName(e.getKey());
			mnemonicIdx.put(e.getKey(), id++);
		}
	}

	// --- Instructions / basic blocks / flow graphs ---------------------------

	private void buildBodies() {
		for (int mi = 0; mi < methods.size(); mi++) {
			MethodNode mth = methods.get(mi);
			List<BlockNode> blocks = blocksByMethod.get(mi);
			if (blocks.isEmpty()) {
				continue; // no body => call graph vertex only, no flow graph
			}
			long base = methodAddress(mi);
			long seq = 0;
			Set<Integer> methodCallees = calleesByMethod.get(mi);
			Map<BlockNode, Integer> blockGlobalIdx = new IdentityHashMap<>();

			for (BlockNode b : blocks) {
				int firstInsn = be.getInstructionCount();
				List<InsnNode> insns = b.getInstructions();
				if (insns.isEmpty()) {
					emitInstruction(null, base + seq++, methodCallees);
				} else {
					for (InsnNode insn : insns) {
						emitInstruction(insn, base + seq++, methodCallees);
					}
				}
				int endExclusive = be.getInstructionCount();

				BinExport2.BasicBlock.IndexRange.Builder range =
						BinExport2.BasicBlock.IndexRange.newBuilder().setBeginIndex(firstInsn);
				if (endExclusive != firstInsn + 1) {
					range.setEndIndex(endExclusive);
				}
				blockGlobalIdx.put(b, be.getBasicBlockCount());
				be.addBasicBlock(BinExport2.BasicBlock.newBuilder().addInstructionIndex(range).build());
			}

			BinExport2.FlowGraph.Builder fg = BinExport2.FlowGraph.newBuilder();
			BlockNode entry = mth.getEnterBlock();
			Integer entryIdx = entry != null ? blockGlobalIdx.get(entry) : null;
			if (entryIdx == null) {
				entryIdx = blockGlobalIdx.get(blocks.get(0));
			}
			fg.setEntryBasicBlockIndex(entryIdx);

			for (BlockNode b : blocks) {
				int src = blockGlobalIdx.get(b);
				fg.addBasicBlockIndex(src);
				List<BlockNode> succs = b.getSuccessors();
				InsnNode last = lastInsn(b);
				for (BlockNode succ : succs) {
					Integer dst = blockGlobalIdx.get(succ);
					if (dst == null) {
						continue;
					}
					BinExport2.FlowGraph.Edge.Builder edge = BinExport2.FlowGraph.Edge.newBuilder()
							.setSourceBasicBlockIndex(src)
							.setTargetBasicBlockIndex(dst)
							.setType(edgeType(last, succs.size(), succ));
					if (isBackEdge(b, succ)) {
						edge.setIsBackEdge(true);
					}
					fg.addEdge(edge.build());
				}
			}
			be.addFlowGraph(fg.build());
		}
	}

	private void emitInstruction(InsnNode insn, long address, Set<Integer> methodCallees) {
		BinExport2.Instruction.Builder ib = BinExport2.Instruction.newBuilder();
		ib.setAddress(address);

		String mnem = insn == null ? NOP : mnemonicOf(insn);
		Integer idx = mnemonicIdx.get(mnem);
		if (idx == null) {
			// Histogram and emission share blocksByMethod, so a miss is a bug;
			// falling back to 0 would silently emit the most frequent mnemonic.
			throw new IllegalStateException("mnemonic missing from histogram: " + mnem);
		}
		if (idx != 0) {
			ib.setMnemonicIndex(idx);
		}
		// Synthetic raw_bytes: a canonical, file-independent rendering of the
		// instruction. BinDiff SDBM-hashes raw_bytes into its function- and
		// basic-block-level "hash matching" steps (its two highest-confidence
		// matchers); with empty bytes every hash is 0 and those steps can never
		// match anything. Safe because we set an address on every instruction,
		// so the byte length is never used to reconstruct addresses.
		ib.setRawBytes(insn == null ? NOP_BYTES : renderBytes(insn));
		if (insn != null) {
			for (int opIndex : buildOperands(insn)) {
				ib.addOperandIndex(opIndex);
			}
			// call_target for every invoke in this insn's tree: jadx folds many
			// invokes into operand trees (InsnWrapArg) and those calls belong to
			// this instruction too. Self-recursion is kept - reference BinExport
			// builders emit it and BinDiff's degree-based matching expects it.
			Set<Integer> callees = new LinkedHashSet<>();
			collectCallees(insn, callees);
			for (int ci : callees) {
				ib.addCallTarget(methodAddress(ci));
				methodCallees.add(ci);
			}
		}
		be.addInstruction(ib.build());
	}

	private List<Integer> buildOperands(InsnNode insn) {
		List<Integer> ops = new ArrayList<>();
		if (insn instanceof BaseInvokeNode) {
			MethodInfo callee = ((BaseInvokeNode) insn).getCallMth();
			if (callee != null) {
				ops.add(calleeOperandIdx.computeIfAbsent(callee,
						c -> operandForSymbol(calleeRawId(c))));
			}
		}
		RegisterArg result = insn.getResult();
		if (result != null) {
			ops.add(operandForArg(result));
		}
		for (InsnArg arg : insn.getArguments()) {
			ops.add(operandForArg(arg));
		}
		return ops;
	}

	private int operandForArg(InsnArg arg) {
		if (arg.isLiteral()) {
			return operandForImmediate(((LiteralArg) arg).getLiteral());
		}
		if (arg.isRegister()) {
			return operandForRegister(((RegisterArg) arg).getRegNum());
		}
		if (arg.isInsnWrap()) {
			InsnNode wrapped = arg.unwrap();
			return operandForSymbol("~" + (wrapped != null ? mnemonicOf(wrapped) : "wrap"));
		}
		return operandForSymbol("arg");
	}

	private int operandForImmediate(long value) {
		// IMMEDIATE_INT is the default expression type, so it is left unset.
		return immOperandIdx.computeIfAbsent(value, v -> addExprOperand(
				BinExport2.Expression.newBuilder().setImmediate(v).build()));
	}

	private int operandForRegister(int regNum) {
		return regOperandIdx.computeIfAbsent(regNum, r -> addExprOperand(
				BinExport2.Expression.newBuilder()
						.setType(BinExport2.Expression.Type.REGISTER)
						.setSymbol("v" + r)
						.build()));
	}

	private int operandForSymbol(String symbol) {
		return symOperandIdx.computeIfAbsent(symbol, s -> addExprOperand(
				BinExport2.Expression.newBuilder()
						.setType(BinExport2.Expression.Type.SYMBOL)
						.setSymbol(s)
						.build()));
	}

	private ByteString renderBytes(InsnNode insn) {
		StringBuilder sb = new StringBuilder(32);
		renderInsn(insn, sb);
		return ByteString.copyFromUtf8(sb.toString());
	}

	/**
	 * Canonical text form of an insn tree: mnemonic, callee id, result/arg
	 * registers, literals, and wrapped insns (recursively). Deterministic and
	 * file-independent, so identical code hashes identically across exports.
	 */
	private void renderInsn(InsnNode insn, StringBuilder sb) {
		sb.append(mnemonicOf(insn));
		if (insn instanceof BaseInvokeNode) {
			MethodInfo callee = ((BaseInvokeNode) insn).getCallMth();
			if (callee != null) {
				sb.append(' ').append(calleeRawId(callee));
			}
		}
		RegisterArg result = insn.getResult();
		if (result != null) {
			sb.append(" v").append(result.getRegNum());
		}
		for (InsnArg arg : insn.getArguments()) {
			sb.append(' ');
			if (arg.isLiteral()) {
				sb.append(((LiteralArg) arg).getLiteral());
			} else if (arg.isRegister()) {
				sb.append('v').append(((RegisterArg) arg).getRegNum());
			} else if (arg.isInsnWrap()) {
				InsnNode wrapped = arg.unwrap();
				if (wrapped != null) {
					sb.append('{');
					renderInsn(wrapped, sb);
					sb.append('}');
				} else {
					sb.append("~wrap");
				}
			} else {
				sb.append("arg");
			}
		}
	}

	private String calleeRawId(MethodInfo callee) {
		return calleeRawIdCache.computeIfAbsent(callee, MethodInfo::getRawFullId);
	}

	private int addExprOperand(BinExport2.Expression expr) {
		int exprIndex = be.getExpressionCount();
		be.addExpression(expr);
		be.addOperand(BinExport2.Operand.newBuilder().addExpressionIndex(exprIndex).build());
		return be.getOperandCount() - 1;
	}

	// --- Call graph + modules ------------------------------------------------

	private void buildCallGraph() {
		BinExport2.CallGraph.Builder cg = be.getCallGraphBuilder();

		// Vertices, in method-index order => addresses are strictly ascending,
		// which BinDiff requires.
		for (int i = 0; i < methods.size(); i++) {
			MethodNode mth = methods.get(i);
			String rawId = rawId(mth);
			BinExport2.CallGraph.Vertex.Builder v = BinExport2.CallGraph.Vertex.newBuilder()
					.setAddress(methodAddress(i))
					.setMangledName(rawId)
					.setModuleIndex(moduleIndex(mth.getParentClass()));
			String demangled = mth.getMethodInfo().getFullId();
			if (!demangled.equals(rawId)) {
				v.setDemangledName(demangled);
			}
			cg.addVertex(v.build());
		}

		// Edges for methods with exported bodies were collected while emitting
		// instruction call targets, so call graph and per-instruction data can
		// never disagree. Bodyless methods (failed class, oversized) still
		// contribute call edges from whatever IR is available.
		for (int i = 0; i < methods.size(); i++) {
			Set<Integer> callees = calleesByMethod.get(i);
			if (blocksByMethod.get(i).isEmpty()) {
				try {
					for (BlockNode b : rawBlocks(methods.get(i))) {
						for (InsnNode insn : b.getInstructions()) {
							collectCallees(insn, callees);
						}
					}
				} catch (Throwable t) {
					// Partially-transformed IR of a failed class may throw anywhere;
					// keep the callees collected so far instead of killing the export.
				}
			}
			for (int t : callees) {
				cg.addEdge(BinExport2.CallGraph.Edge.newBuilder()
						.setSourceVertexIndex(i)
						.setTargetVertexIndex(t)
						.build());
			}
		}
	}

	private void collectCallees(InsnNode insn, Set<Integer> out) {
		Integer ci = calleeIndex(insn);
		if (ci != null) {
			out.add(ci);
		}
		for (InsnArg arg : insn.getArguments()) {
			if (arg.isInsnWrap()) {
				InsnNode wrapped = arg.unwrap();
				if (wrapped != null) {
					collectCallees(wrapped, out);
				}
			}
		}
	}

	/** Single source of truth for resolving an insn to a callee method index. */
	private Integer calleeIndex(InsnNode insn) {
		if (!(insn instanceof BaseInvokeNode)) {
			return null;
		}
		MethodInfo callee = ((BaseInvokeNode) insn).getCallMth();
		if (callee == null) {
			return null;
		}
		// Misses (external callees, the common case) are cached via sentinel -
		// computeIfAbsent would drop a null mapping and recompute every time.
		int idx = calleeIdxCache.computeIfAbsent(callee, c -> {
			Integer i = methodIndexByRawId.get(calleeRawId(c));
			return i != null ? i : NOT_IN_APP;
		});
		return idx == NOT_IN_APP ? null : idx;
	}

	private int moduleIndex(ClassNode cls) {
		return moduleIdx.computeIfAbsent(cls.getClassInfo().getFullName(), name -> {
			be.addModule(BinExport2.Module.newBuilder().setName(name).build());
			return be.getModuleCount() - 1;
		});
	}

	// --- Meta / output -------------------------------------------------------

	private void buildMeta(JadxArgs args) {
		File input = firstInput(args);
		String name = input != null ? input.getName() : "app";
		// Two .BinExport files are only comparable when produced by the same
		// jadx version (IR normalization/folding differ). Meta is display-only
		// for BinDiff, so carrying the version in architecture_name surfaces a
		// mismatch right in the BinDiff UI / results DB.
		be.getMetaInformationBuilder()
				.setExecutableName(name)
				.setExecutableId(sha256(input, name))
				.setArchitectureName("dalvik-jadx-" + Jadx.getVersion())
				.setTimestamp(System.currentTimeMillis() / 1000);
	}

	private File resolveOutputFile(JadxArgs args) {
		String override = options.getOutput();
		if (override != null && !override.isEmpty()) {
			return new File(override);
		}
		File input = firstInput(args);
		String base = input != null ? CommonFileUtils.removeFileExtension(input.getName()) : "app";
		File dir;
		String outdir = options.getOutDir();
		if (outdir != null && !outdir.isEmpty()) {
			dir = new File(outdir);
		} else if (args.getOutDir() != null) {
			dir = args.getOutDir();
		} else {
			dir = new File(".");
		}
		return new File(dir, base + ".BinExport");
	}

	private static File firstInput(JadxArgs args) {
		List<File> inputs = args.getInputFiles();
		return inputs == null || inputs.isEmpty() ? null : inputs.get(0);
	}

	private static String sha256(File file, String fallback) {
		if (file == null || !file.isFile()) {
			return fallback;
		}
		try (InputStream in = Files.newInputStream(file.toPath())) {
			MessageDigest md = MessageDigest.getInstance("SHA-256");
			byte[] buf = new byte[64 * 1024];
			int n;
			while ((n = in.read(buf)) != -1) {
				md.update(buf, 0, n);
			}
			byte[] hash = md.digest();
			StringBuilder sb = new StringBuilder(hash.length * 2);
			for (byte x : hash) {
				sb.append(Character.forDigit((x >> 4) & 0xF, 16));
				sb.append(Character.forDigit(x & 0xF, 16));
			}
			return sb.toString();
		} catch (Exception e) {
			return fallback;
		}
	}

	// --- Small helpers -------------------------------------------------------

	/** Links vertex addresses, instruction bases and call targets - keep single. */
	private static long methodAddress(int methodIndex) {
		return (methodIndex + 1L) * METHOD_ADDR_STRIDE;
	}

	private String rawId(MethodNode mth) {
		return rawIdCache.computeIfAbsent(mth, m -> m.getMethodInfo().getRawFullId());
	}

	private static InsnNode lastInsn(BlockNode b) {
		List<InsnNode> insns = b.getInstructions();
		return insns.isEmpty() ? null : insns.get(insns.size() - 1);
	}

	private static BinExport2.FlowGraph.Edge.Type edgeType(InsnNode last, int succCount, BlockNode succ) {
		if (last == null || succCount <= 1) {
			return BinExport2.FlowGraph.Edge.Type.UNCONDITIONAL;
		}
		if (last.getType() == InsnType.SWITCH) {
			return BinExport2.FlowGraph.Edge.Type.SWITCH;
		}
		if (last instanceof IfNode) {
			// jadx keeps then/else on the IfNode itself; successor list order is
			// unrelated (the fall-through/else edge is usually connected first,
			// and invertCondition() swaps then/else without reordering it).
			IfNode ifInsn = (IfNode) last;
			if (succ == ifInsn.getThenBlock()) {
				return BinExport2.FlowGraph.Edge.Type.CONDITION_TRUE;
			}
			if (succ == ifInsn.getElseBlock()) {
				return BinExport2.FlowGraph.Edge.Type.CONDITION_FALSE;
			}
		}
		return BinExport2.FlowGraph.Edge.Type.UNCONDITIONAL;
	}

	private static boolean isBackEdge(BlockNode from, BlockNode to) {
		if (from == to) {
			return true;
		}
		try {
			// Back edge <=> target dominates source.
			return from.getDoms() != null && from.getDoms().get(to.getPos());
		} catch (Throwable t) {
			return false;
		}
	}

	private static String mnemonicOf(InsnNode insn) {
		return MNEMONIC_BY_TYPE.get(insn.getType());
	}

	private static Map<InsnType, String> buildMnemonicTable() {
		Map<InsnType, String> map = new EnumMap<>(InsnType.class);
		for (InsnType type : InsnType.values()) {
			map.put(type, type.name().toLowerCase(Locale.ROOT).replace('_', '-'));
		}
		return map;
	}
}
