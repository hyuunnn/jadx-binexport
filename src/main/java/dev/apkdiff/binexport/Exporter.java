package dev.apkdiff.binexport;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.google.security.zynamics.BinExport.BinExport2;
import com.google.security.zynamics.BinExport.BinExport2.Builder;

import jadx.api.JadxArgs;
import jadx.api.JadxDecompiler;
import jadx.api.JavaClass;
import jadx.core.dex.info.MethodInfo;
import jadx.core.dex.instructions.BaseInvokeNode;
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

	/** Room for up to 2^20 (~1M) instructions per method before addresses collide. */
	private static final long METHOD_ADDR_SHIFT = 20;
	private static final long METHOD_ADDR_STRIDE = 1L << METHOD_ADDR_SHIFT;

	private static final String NOP = "nop";

	private final Builder be = BinExport2.newBuilder();

	private final List<MethodNode> methods = new ArrayList<>();
	private final Map<String, Integer> methodIndexByRawId = new HashMap<>();

	// De-dup tables (mirrors BinExport's own de-duplication scheme).
	private final Map<BinExport2.Expression, Integer> exprIdx = new HashMap<>();
	private final Map<BinExport2.Operand, Integer> operandIdx = new HashMap<>();
	private final Map<String, Integer> mnemonicIdx = new HashMap<>();
	private final Map<String, Integer> moduleIdx = new HashMap<>();

	private Exporter() {
	}

	/** Entry point: build and write the .BinExport file, returning its path. */
	public static File run(JadxDecompiler decompiler) {
		return new Exporter().export(decompiler);
	}

	private File export(JadxDecompiler decompiler) {
		// 1. Force decompilation and collect every method (top-level + nested).
		Set<ClassNode> visited = Collections.newSetFromMap(new IdentityHashMap<>());
		for (JavaClass jc : decompiler.getClasses()) {
			collect(jc.getClassNode(), visited);
		}

		// 2. Deterministic method ordering => stable synthetic addresses.
		methods.sort(Comparator.comparing(m -> m.getMethodInfo().getRawFullId()));
		for (int i = 0; i < methods.size(); i++) {
			methodIndexByRawId.put(methods.get(i).getMethodInfo().getRawFullId(), i);
		}

		// 3. Global tables and graphs (order matters for index bookkeeping).
		buildMnemonics();
		buildBodies();
		buildCallGraph();
		buildMeta(decompiler.getArgs());

		// 4. Serialize.
		File out = resolveOutputFile(decompiler.getArgs());
		File parent = out.getParentFile();
		if (parent != null) {
			parent.mkdirs();
		}
		try (OutputStream os = Files.newOutputStream(out.toPath())) {
			be.build().writeTo(os);
		} catch (IOException e) {
			throw new RuntimeException("BinExport write failed: " + out, e);
		}
		System.out.println("[BinExport] wrote " + methods.size() + " methods, "
				+ be.getInstructionCount() + " instructions to " + out.getAbsolutePath());
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
			System.err.println("[BinExport] process failed for " + cls + ": " + t);
		}
		methods.addAll(cls.getMethods());
		for (ClassNode inner : cls.getInnerClasses()) {
			collect(inner, visited);
		}
		for (ClassNode inlined : cls.getInlinedClasses()) {
			collect(inlined, visited);
		}
	}

	// --- Mnemonics -----------------------------------------------------------

	private void buildMnemonics() {
		Map<String, Integer> hist = new HashMap<>();
		for (MethodNode mth : methods) {
			for (BlockNode b : safeBlocks(mth)) {
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
			List<BlockNode> blocks = safeBlocks(mth);
			if (blocks.isEmpty()) {
				continue; // no body => call graph vertex only, no flow graph
			}
			long base = (long) (mi + 1) * METHOD_ADDR_STRIDE;
			long seq = 0;
			Map<BlockNode, Integer> blockGlobalIdx = new IdentityHashMap<>();

			for (BlockNode b : blocks) {
				int firstInsn = be.getInstructionCount();
				List<InsnNode> insns = b.getInstructions();
				if (insns.isEmpty()) {
					emitInstruction(null, base + seq++);
				} else {
					for (InsnNode insn : insns) {
						emitInstruction(insn, base + seq++);
					}
				}
				int endExclusive = be.getInstructionCount();

				int bbGlobal = be.getBasicBlockCount();
				BinExport2.BasicBlock.Builder bb = be.addBasicBlockBuilder();
				BinExport2.BasicBlock.IndexRange.Builder range =
						bb.addInstructionIndexBuilder().setBeginIndex(firstInsn);
				if (endExclusive != firstInsn + 1) {
					range.setEndIndex(endExclusive);
				}
				blockGlobalIdx.put(b, bbGlobal);
			}

			BinExport2.FlowGraph.Builder fg = be.addFlowGraphBuilder();
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
				InsnType lastType = lastType(b);
				for (int si = 0; si < succs.size(); si++) {
					Integer dst = blockGlobalIdx.get(succs.get(si));
					if (dst == null) {
						continue;
					}
					BinExport2.FlowGraph.Edge.Builder edge = fg.addEdgeBuilder()
							.setSourceBasicBlockIndex(src)
							.setTargetBasicBlockIndex(dst)
							.setType(edgeType(lastType, succs.size(), si));
					if (isBackEdge(b, succs.get(si))) {
						edge.setIsBackEdge(true);
					}
				}
			}
		}
	}

	private void emitInstruction(InsnNode insn, long address) {
		BinExport2.Instruction.Builder ib = be.addInstructionBuilder();
		ib.setAddress(address);

		String mnem = insn == null ? NOP : mnemonicOf(insn);
		int idx = mnemonicIdx.getOrDefault(mnem, 0);
		if (idx != 0) {
			ib.setMnemonicIndex(idx);
		}
		if (insn == null) {
			return;
		}
		for (int opIndex : buildOperands(insn)) {
			ib.addOperandIndex(opIndex);
		}
		if (insn instanceof BaseInvokeNode) {
			MethodInfo callee = ((BaseInvokeNode) insn).getCallMth();
			if (callee != null) {
				Integer ci = methodIndexByRawId.get(callee.getRawFullId());
				if (ci != null) {
					ib.addCallTarget((long) (ci + 1) * METHOD_ADDR_STRIDE);
				}
			}
		}
	}

	private List<Integer> buildOperands(InsnNode insn) {
		List<Integer> ops = new ArrayList<>();
		if (insn instanceof BaseInvokeNode) {
			MethodInfo callee = ((BaseInvokeNode) insn).getCallMth();
			if (callee != null) {
				ops.add(operandOf(symbolExpr(callee.getRawFullId())));
			}
		}
		RegisterArg result = insn.getResult();
		if (result != null) {
			ops.add(operandOf(argExpr(result)));
		}
		for (InsnArg arg : insn.getArguments()) {
			ops.add(operandOf(argExpr(arg)));
		}
		return ops;
	}

	private BinExport2.Expression argExpr(InsnArg arg) {
		if (arg.isLiteral()) {
			// IMMEDIATE_INT is the default type, so it is left unset.
			return BinExport2.Expression.newBuilder()
					.setImmediate(((LiteralArg) arg).getLiteral())
					.build();
		}
		if (arg.isRegister()) {
			return BinExport2.Expression.newBuilder()
					.setType(BinExport2.Expression.Type.REGISTER)
					.setSymbol("v" + ((RegisterArg) arg).getRegNum())
					.build();
		}
		if (arg.isInsnWrap()) {
			InsnNode wrapped = arg.unwrap();
			return symbolExpr("~" + (wrapped != null ? mnemonicOf(wrapped) : "wrap"));
		}
		return symbolExpr("arg");
	}

	private static BinExport2.Expression symbolExpr(String symbol) {
		return BinExport2.Expression.newBuilder()
				.setType(BinExport2.Expression.Type.SYMBOL)
				.setSymbol(symbol)
				.build();
	}

	private int operandOf(BinExport2.Expression expr) {
		int exprIndex = getOrAddExpr(expr);
		BinExport2.Operand op = BinExport2.Operand.newBuilder().addExpressionIndex(exprIndex).build();
		return getOrAddOperand(op);
	}

	private int getOrAddExpr(BinExport2.Expression expr) {
		Integer i = exprIdx.get(expr);
		if (i != null) {
			return i;
		}
		int id = be.getExpressionCount();
		be.addExpression(expr);
		exprIdx.put(expr, id);
		return id;
	}

	private int getOrAddOperand(BinExport2.Operand op) {
		Integer i = operandIdx.get(op);
		if (i != null) {
			return i;
		}
		int id = be.getOperandCount();
		be.addOperand(op);
		operandIdx.put(op, id);
		return id;
	}

	// --- Call graph + modules ------------------------------------------------

	private void buildCallGraph() {
		BinExport2.CallGraph.Builder cg = be.getCallGraphBuilder();

		// Vertices, in method-index order => addresses are strictly ascending,
		// which BinDiff requires.
		for (int i = 0; i < methods.size(); i++) {
			MethodNode mth = methods.get(i);
			MethodInfo mi = mth.getMethodInfo();
			BinExport2.CallGraph.Vertex.Builder v = cg.addVertexBuilder()
					.setAddress((long) (i + 1) * METHOD_ADDR_STRIDE)
					.setMangledName(mi.getRawFullId())
					.setModuleIndex(moduleIndex(mth.getParentClass()));
			String demangled = mi.getFullId();
			if (!demangled.equals(mi.getRawFullId())) {
				v.setDemangledName(demangled);
			}
		}

		// Edges: dedup callees per method, recursing into wrapped (inlined) insns
		// because jadx folds many invokes into operand trees.
		for (int i = 0; i < methods.size(); i++) {
			Set<Integer> targets = new LinkedHashSet<>();
			for (BlockNode b : safeBlocks(methods.get(i))) {
				for (InsnNode insn : b.getInstructions()) {
					collectCallees(insn, i, targets);
				}
			}
			for (int t : targets) {
				cg.addEdgeBuilder().setSourceVertexIndex(i).setTargetVertexIndex(t);
			}
		}
	}

	private void collectCallees(InsnNode insn, int selfIdx, Set<Integer> out) {
		if (insn instanceof BaseInvokeNode) {
			MethodInfo callee = ((BaseInvokeNode) insn).getCallMth();
			if (callee != null) {
				Integer ci = methodIndexByRawId.get(callee.getRawFullId());
				if (ci != null && ci != selfIdx) {
					out.add(ci);
				}
			}
		}
		for (InsnArg arg : insn.getArguments()) {
			if (arg.isInsnWrap()) {
				InsnNode wrapped = arg.unwrap();
				if (wrapped != null) {
					collectCallees(wrapped, selfIdx, out);
				}
			}
		}
	}

	private int moduleIndex(ClassNode cls) {
		String name = cls.getClassInfo().getFullName();
		Integer i = moduleIdx.get(name);
		if (i != null) {
			return i;
		}
		int id = be.getModuleCount();
		be.addModuleBuilder().setName(name);
		moduleIdx.put(name, id);
		return id;
	}

	// --- Meta / output -------------------------------------------------------

	private void buildMeta(JadxArgs args) {
		File input = firstInput(args);
		String name = input != null ? input.getName() : "app";
		be.getMetaInformationBuilder()
				.setExecutableName(name)
				.setExecutableId(sha256(input, name))
				.setArchitectureName("dalvik")
				.setTimestamp(System.currentTimeMillis() / 1000);
	}

	private File resolveOutputFile(JadxArgs args) {
		String override = System.getProperty("binexport.output");
		if (override != null && !override.isEmpty()) {
			return new File(override);
		}
		File input = firstInput(args);
		String base = input != null ? stripExt(input.getName()) : "app";
		File dir;
		String outdir = System.getProperty("binexport.outdir");
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

	private static String stripExt(String fileName) {
		int dot = fileName.lastIndexOf('.');
		return dot > 0 ? fileName.substring(0, dot) : fileName;
	}

	private static String sha256(File file, String fallback) {
		if (file == null || !file.isFile()) {
			return fallback;
		}
		try {
			MessageDigest md = MessageDigest.getInstance("SHA-256");
			byte[] hash = md.digest(Files.readAllBytes(file.toPath()));
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

	private static List<BlockNode> safeBlocks(MethodNode mth) {
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

	private static InsnType lastType(BlockNode b) {
		List<InsnNode> insns = b.getInstructions();
		return insns.isEmpty() ? null : insns.get(insns.size() - 1).getType();
	}

	private static BinExport2.FlowGraph.Edge.Type edgeType(InsnType last, int succCount, int index) {
		if (succCount <= 1) {
			return BinExport2.FlowGraph.Edge.Type.UNCONDITIONAL;
		}
		if (last == InsnType.SWITCH) {
			return BinExport2.FlowGraph.Edge.Type.SWITCH;
		}
		if (last == InsnType.IF && succCount == 2) {
			return index == 0
					? BinExport2.FlowGraph.Edge.Type.CONDITION_TRUE
					: BinExport2.FlowGraph.Edge.Type.CONDITION_FALSE;
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
		return insn.getType().name().toLowerCase(Locale.ROOT).replace('_', '-');
	}
}
