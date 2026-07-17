# CLAUDE.md — apk-diff / jadx → BinExport plugin

Working notes for continuing development. Reads best top-to-bottom once, then as a reference.

## What this is

A **jadx plugin** that exports jadx's analysis (classes, methods, CFG, call graph)
into the **BinExport2 protobuf** consumed by **BinDiff**, so two APKs' Dalvik
bytecode can be diffed/visualized in BinDiff. The plugin does *pure mapping*: jadx
already computes everything (methods, basic blocks, dominators, call refs); we
serialize it. The proto was designed with DEX in mind (`Architecture::kDex`,
`Module` = Java class, `Vertex.module_index`), so this is an intended use, not a hack.

## Layout

```
src/main/proto/binexport2.proto                     # vendored verbatim from google/binexport
src/main/java/dev/apkdiff/binexport/
  BinExportPlugin.java     # JadxPlugin. CLI/library => auto (SimpleAfterLoadPass); GUI => 2 menu actions
  BinExportOptions.java    # per-instance plugin options (output/outdir), sysprop fallback
  Exporter.java            # THE mapping logic (jadx model -> BinExport2)
  BinDiffResults.java      # reads a .BinDiff (SQLite) + builds mangledName->MethodNode index (testable core)
  BinDiffRunner.java       # in-GUI diff: export current app + shell out to bindiff => .BinDiff
  BinDiffResultsPanel.java # GUI-only: navigable results table, double-click => jump to method
src/main/resources/META-INF/services/jadx.api.plugins.JadxPlugin   # plugin registration
src/test/java/.../ExporterIntegrationTest.java      # real jadx E2E test
```

Plugin id: `apk-diff-binexport`. Output jar: `build/libs/apk-diff-binexport-<ver>.jar`.

## Build / test / verify

```bash
./gradlew build            # generateProto -> compile -> test -> shadowJar
./gradlew test             # E2E integration test only
```

- JDK 21 present; bytecode target is **Java 11** (`options.release = 11`) so the jar runs on any jadx-supported JRE.
- Versions: jadx **1.5.6**, protobuf-java/protoc **3.25.5**, shadow `com.gradleup.shadow` **8.3.5**, protobuf-gradle-plugin **0.9.4**, gradle wrapper **8.10.2** (committed; just use `./gradlew`).
- `jadx-core` is `compileOnly` (provided by jadx at runtime). protobuf is `implementation` and **shaded + relocated** `com.google.protobuf` -> `dev.apkdiff.shadow.protobuf` to avoid clashing with jadx's own protobuf. The plain `jar` task is disabled; the shadow jar is the artifact.
- Generated proto entry class: `com.google.security.zynamics.BinExport.BinExport2` (from the proto's `java_package` + `java_outer_classname`; NOT relocated).

## Install / run

```bash
jadx plugins --install-jar build/libs/apk-diff-binexport-0.1.0.jar
jadx -d out_v1 app-v1.apk        # -> out_v1/app-v1.BinExport
jadx -d out_v2 app-v2.apk        # -> out_v2/app-v2.BinExport
```
Output path resolution (first wins): plugin option `apk-diff-binexport.output` (legacy `-Dbinexport.output`) → `apk-diff-binexport.outdir` (legacy `-Dbinexport.outdir`) → jadx out dir. Existing files are overwritten with a log warning. GUI: `Plugins → Export to BinExport`.

## GOTCHAS (hard-won — read before touching Exporter)

1. **`cls.decompile()` DESTROYS the CFG.** `ProcessClass.process(cls, codegen=true)` calls `cls.unload()` right after code generation, nulling `MethodNode.blocks`. Symptom: export runs but `instructions=0`, empty flow graphs. Fix in use: `cls.root().getProcessClasses().forceProcess(cls)` runs the decompile passes (block split, SSA, dominators, region maker) **without** codegen/unload, leaving blocks intact and state `PROCESS_COMPLETE`. Normal `jadx.save()` afterwards still works (codegen from the already-processed state).

2. **jadx has no "after decompile" hook.** Pass types are only `JadxPreparePass`, `JadxDecompilePass`, `JadxAfterLoadPass`. `JadxAfterLoadPass.init(decompiler)` fires at the *end of `load()`*, **before** any decompilation. So the pass must drive decompilation itself (that's what `forceProcess` above is for) and then write the file. We use jadx's `SimpleAfterLoadPass` (no custom pass class needed). There is no streaming "last method" signal — the after-load-drives-everything design gives a clean single-threaded pass with a definite end.

3. **GUI vs CLI branching.** `context.getGuiContext() != null` ⇒ GUI: register an on-demand menu action (don't auto-export the whole app on every project open). Else CLI/library: register the AfterLoadPass. Both call `Exporter.runLogged(decompiler, options)` (single error contract, slf4j — `System.out` never reaches jadx-gui's Log Viewer). The GUI action fetches `context.getDecompiler()` at **click time** (the init-time instance can go stale on project reload) and an `AtomicBoolean` rejects concurrent runs (two exports would race on one output file).

3a. **Use `getRoot().getClasses()`, NOT `decompiler.getClasses()`.** The latter filters out classes flagged `AFlag.DONT_GENERATE` (deduplicated multidex entries, synthetic holders) — their methods would silently vanish from the call graph, producing phantom diffs. `getRoot().getClasses()` enumerates the full model (including inner classes; the visited-set handles the overlap with the inner/inlined recursion).

3b. **IF edge labels come from `IfNode.getThenBlock()/getElseBlock()`, NEVER successor order.** jadx connects the fall-through (else) successor first, and `invertCondition()` swaps then/else *without* reordering successors — "first successor = TRUE" is inverted in the common case. Verified against jadx 1.5.6 sources.

4. **jadx IR is normalized, NOT raw smali.** Instructions are jadx's post-transform IR (`InsnType`), and many `invoke`s get **folded into operand trees** as `InsnWrapArg`. Consequences:
   - Call-graph completeness requires recursing wrapped args (`arg.isInsnWrap()` → `arg.unwrap()`), else you miss calls. `Exporter.collectCallees` does this, and `emitInstruction` runs it per instruction so folded invokes also get `call_target` — callees are accumulated per method there, so the call graph and per-instruction data can never disagree (single IR sweep, no separate call-graph pass). Self-recursion edges are **kept** (BinDiff's degree-based matching expects them).
   - Two files are only comparable in BinDiff if produced by the **same jadx version** (mnemonic set + folding differ across versions). This is a hard rule, surface it to users.

5. **Empty/synthetic blocks.** jadx enter/exit blocks often have 0 instructions. A `BasicBlock` needs ≥1 instruction for its index range, so we emit a synthetic `nop` for empty blocks (kept in the mnemonic histogram too). Don't "optimize" these away or CFG edges break.

6. **Degraded-body accounting.** `resolveBlocks` decides ONCE which methods export with a body; all three passes share `blocksByMethod`. A method exports as a bodyless call-graph vertex when: it has no code; its top-level class failed `forceProcess` (partially-transformed IR would export misleading bodies — failures are counted and summarized at the end); or its emitted instruction count would spill past the 2^20 per-method address stride (addresses would stop being unique — logged per method). Bodyless methods **still contribute call-graph edges** — `buildCallGraph` walks their `rawBlocks` for callees; only the flow graph/instructions are withheld. Duplicate `getRawFullId()`s (same class in two inputs) resolve to the **first** copy with a warning.

7. **Enter block must be emitted FIRST.** BinDiff resolves a flow graph to its function via the entry block's first-instruction address == vertex address. jadx normally keeps the enter block at `getBasicBlocks().get(0)`, but `BlockProcessor.insertPreHeader` (method body starting with a loop) creates a fresh enter block via `startNewBlock`, which APPENDS to the list. `resolveBlocks` reorders the enter block to the front before emission-order address assignment; without it BinDiff throws `std::runtime_error("couldn't find call graph node for flow graph")` and aborts the entire diff.

8. **Synthetic `raw_bytes` are load-bearing for obfuscated diffs.** We emit a canonical text rendering of each insn tree (mnemonic, callee id, regs, literals, wrapped insns) as `raw_bytes`. BinDiff SDBM-hashes these into its function/basic-block "hash matching" steps — its two highest-confidence matchers. With empty bytes every hash is 0 and those steps can never match. On same-name inputs the effect is small (name matching runs first), but when ProGuard/R8 renames symbols between versions — the primary use case — content hashes become the top signal. Rendering must stay deterministic and file-independent (no operand/expression INDICES, only content).

8a. **In-GUI diffing (IDA-like), 3 GUI menu actions total.** "Diff against BinExport (.BinExport)…" is the IDA-plugin-style one-step flow: `BinDiffRunner.diff` exports the current app to a temp file (`Exporter.runToFile`), shells out to the `bindiff` executable (found via the `apk-diff-binexport.bindiff` option / PATH / common paths; detected by `--help` OUTPUT since it exits non-zero), and feeds the produced `.BinDiff` straight into the results browser. The matching engine is bindiff itself — we never reimplement it. Note the asymmetry: `.BinExport` is **written** (export) — there is deliberately no "open a .BinExport" reader (the app it represents is already loaded, and it carries no source); `.BinDiff` is **read** (browse/navigate).

9. **BinDiff results browser (GUI, reverse direction).** A GUI menu action ("Open BinDiff results (.BinDiff)…") reads a BinDiff results DB and shows a navigable table; double-click => `JadxGuiContext.open(methodNode)` jumps to the method. Key points: (a) `.BinDiff` is a **SQLite** file — its `function` table has `name1/name2/similarity/confidence`, and `name1/name2` are exactly our `mangled_name` (`MethodInfo.getRawFullId()`), so navigation is **by name, not by the synthetic address**. (b) `BinDiffResults.methodIndex` MUST enumerate methods the SAME way `Exporter` does (`getRoot().getClasses()` + inner/inlined, includes `<init>`) — using the API-level `JavaClass.getMethods()` drops constructors and misses matches. (c) `MethodNode implements ICodeNode extends ICodeNodeRef`, so it is passed straight to `open()`. (d) SQLite via `org.xerial:sqlite-jdbc`, **bundled but NOT relocated** (jadx has no SQLite so no clash; relocating `org.sqlite` breaks its native-lib resource lookup). Use `SQLiteDataSource` (not `DriverManager`) to dodge the child-classloader SPI problem in the plugin classloader. Testable core (`BinDiffResults`) is separate from the Swing shell (`BinDiffResultsPanel`) because Swing can't instantiate headless.

10. **Sysprop passing on the jadx CLI.** jadx has NO `-J` JVM-arg passthrough (unknown options become input files via JCommander's `acceptUnknownOptions`). Legacy sysprops must go through `JADX_OPTS`/`JAVA_OPTS` env vars; the plugin options (`-Papk-diff-binexport.output=...`) are the primary interface. jadx calls `setOptions` during `registerOptions()` in every mode (CLI/GUI/library), and `BasePluginOptionsBuilder` invokes setters with `defaultValue` for absent keys.

## BinExport2 format rules (from the canonical reference)

Canonical reference is Google's Ghidra exporter `java/src/main/java/com/google/security/binexport/BinExport2Builder.java` in `google/binexport`. Mirror it, not guesses.

- **FlowGraph indices are GLOBAL.** `entry_basic_block_index`, `basic_block_index`, and edge `source/target_basic_block_index` all index into the top-level `BinExport2.basic_block` array — NOT into the flow graph's local list.
- **Mnemonic index 0 = most frequent** (build a histogram, sort desc by count then name). Omit `mnemonic_index` when it's 0.
- **BinDiff consumer contract** (verified against google/bindiff source, `flow_graph.cc`/`call_graph.cc`/`match/*`): hard rules are (1) call-graph vertices sorted by address, (2) each flow graph's blocks sorted by first-instruction address, (3) every BasicBlock has ≥1 instruction range (`CHECK`), (4) valid edge enum, (5) **flow-graph entry address == a call-graph vertex address** or `AttachFlowGraph` throws and aborts the diff (why we force the enter block to the front, see gotcha 8). Matching consumes ONLY: mnemonic strings (prime products + LCS), `raw_bytes` (SDBM hashes → the two top-confidence "hash matching" steps), `call_target`, mangled/demangled names (name hash matching is default step #1), and graph topology. **Ignored by matching**: operands/expressions, string_table/`string_reference` (BinDiff hardcodes `string_hash_=0`, TODO in their code), CONDITION_TRUE/FALSE (display only), `is_back_edge` (never read; BinDiff recomputes loops itself), meta_information (display only). BinDiff silently discards function bodies ≥10k instructions / ≥5k blocks / ≥5k edges (we export them anyway and log a count).
- **Vertices MUST be sorted by ascending `address`** (BinDiff requirement). We add them in method-index order and give method `i` address `(i+1) << 20`, so ordering is automatic; `vertexIndex == methodIndex`.
- `Instruction.address` is optional (fill only on discontinuity); we fill it on **every** instruction (simpler, valid).
- `Expression` default type is `IMMEDIATE_INT` → leave `type` unset for immediates (matches "omit defaults" intent).
- De-dup `Expression`/`Operand` keyed by the primitive that fully determines them (literal long / reg num / symbol string — every operand here is a single expression), so the hit path allocates no throwaway protos; same result as BinExport's proto-keyed scheme. Instructions/blocks are NOT deduped — we emit them method-by-method so each block's instructions are a contiguous `[begin,end)` range. Instructions/blocks/flow-graphs are added as **built messages** (`addInstruction(msg)`), not via `addXxxBuilder()` — repeated-field builder mode would hold millions of mutable wrappers in memory.

## Address synthesis

Dalvik has no linear address space. Scheme: sort methods by `MethodInfo.getRawFullId()` (cached per method — jadx rebuilds the string on every call), method `i` → base `methodAddress(i) = (i+1) << 20`; instruction address = `base + sequenceIndex`. `1<<20` (~1M) headroom per method; methods that would exceed it are exported bodyless with a warning (see gotcha 6). `methodAddress()` is the **single** helper linking vertex addresses, instruction bases and `call_target` — never inline the formula. Addresses only need to be **unique + stable within one file** (BinDiff matches structurally, not by absolute address); stable ordering keeps the same method at the same address across rebuilds of the same input.

## Key jadx API cheat-sheet

- `JadxDecompiler.getRoot().getClasses()` → full `List<ClassNode>` incl. inner + `DONT_GENERATE` (what we use). `decompiler.getClasses()` → filtered `List<JavaClass>` (drops `DONT_GENERATE`; don't use for export).
- `ClassNode`: `root()`, `getMethods()`, `getInnerClasses()`, `getInlinedClasses()`, `getClassInfo().getFullName()`.
- `MethodNode`: `getMethodInfo()`, `isNoCode()`, `getBasicBlocks()` (null/empty until processed), `getEnterBlock()`, `getParentClass()`.
- `MethodInfo`: `getRawFullId()` (stable, `$`-separated inner), `getFullId()` (readable), `getShortId()`.
- `BlockNode`: `getInstructions()`, `getSuccessors()`, `getPos()`, `getDoms()` (BitSet) — back edge ⇔ target dominates source: `from.getDoms().get(to.getPos())`.
- `InsnNode`: `getType()` (`InsnType`), `getResult()`, `getArguments()`, `getOffset()`.
- Calls: `insn instanceof BaseInvokeNode` → `getCallMth()` (`MethodInfo`). Args: `InsnArg.isLiteral()/isRegister()/isInsnWrap()`, `LiteralArg.getLiteral()`, `RegisterArg.getRegNum()`.

## Test approach

`ExporterIntegrationTest` compiles a small class with `ToolProvider` javac, loads it through jadx via **`jadx-java-input`** (compiled `.class` path, not DEX), lets the AfterLoadPass export, then `BinExport2.parseFrom` and asserts invariants (sorted vertices, entry∈blocks, global indices in range, valid instruction ranges, ≥1 call edge, a back edge from the loop, CONDITION_TRUE **and** CONDITION_FALSE edges present, a self edge for the recursive `fact()`, ≥1 instruction with `call_target`). Last run: `vertices=5 edges=3 instructions=37 basicBlocks=32 flowGraphs=5 mnemonics=6 backEdge=true`.

## Verification status

- **Real APK/DEX path: VERIFIED** (2026-07-18). `RealApkSmokeTest` (opt-in: `./gradlew test -Pbinexport.smoke.apk=/path/app.apk`) ran a real 1.3MB APK through jadx-dex-input 1.5.6: 13,226 vertices / 10,168 call edges / 90,106 instructions / 11,807 flow graphs, all structural invariants held. jadx's own RegionMaker failures on 2 classes exercised the degraded-body path correctly (bodyless vertices + warning).
- **End-user plugin path: VERIFIED** (2026-07-18). Shadow jar installed into Homebrew jadx **1.5.5** (`jadx plugins --install-jar`), exported the same APK via CLI auto-pass; output parsed back with the relocated protobuf (`PARSE_OK`, sorted vertices). So the relocation works and the plugin is API-compatible down to at least 1.5.5. Note: `jadx plugins` shows local-jar installs as `version null` — that's jadx's `LocalFileResolver` (never sets a version for file installs), not a plugin bug.
- **BinDiff ingestion: VERIFIED** (2026-07-18, BinDiff 8 CLI). Self-diff of two independent exports of the same APK: **13,226/13,226 functions matched**, similarity 97.81%. Cross-diff of two different APKs sharing the android support library: ~9.6k functions matched, similarity 68.2% / confidence 95.4%, `.BinDiff` results written. Full pipeline (jadx plugin → .BinExport → BinDiff match → results DB) works end-to-end. Notes: identical exports scoring <100% is BinDiff's formula (stub/bodyless vertices), not an exporter bug — the score is stable across runs.
- **Obfuscated-rename benchmark: VERIFIED, and it justifies `raw_bytes`** (2026-07-18). Built one 42-method sample app, obfuscated it TWICE with R8 using **disjoint obfuscation dictionaries** so every method's name differs between the two builds (names carry zero matching signal — the real v1-vs-v2 diff scenario). Ground truth from R8's `mapping.txt` on each side. Exported both DEX files through the plugin, diffed in BinDiff, scored each match against ground truth:
  - **raw_bytes ON: 50/52 correct (96.2%)** — matches came from flow/call-graph MD-index (31) + `function: hash matching` (15, the step that consumes raw_bytes); name matching only 1. The 2 "wrong" are a genuinely-new v2 method paired to a structurally similar one (no correct answer exists).
  - **raw_bytes OFF (A/B control): 12/52 correct (23.1%)** — MD-index steps collapse (the sample's methods share a near-identical loop/branch skeleton, so topology MD indices collide and can't disambiguate) and BinDiff falls back to `function: address sequence` (39), which is meaningless across two independent obfuscations.
  - Conclusion: on obfuscated, structurally-repetitive code, `raw_bytes` (which encodes the per-method constants/operands that break MD-index ties) is the difference between **96% and 23%** correct matching. Not a marginal nice-to-have.

## Backlog / next improvements

1. Add `IMPORTED` vertices + call edges for framework/external calls (currently only in-app edges); consider typing no-code (abstract/native) vertices as non-NORMAL too.
2. Memory: all classes stay loaded (no unload) — fine for small/medium apps, may need streaming for huge multidex (proto side already uses immutable adds, so the builder overhead is gone).
3. Consider exporting native `lib/*.so` via existing BinExport ARM/AArch64 path for a full "APK diff" (out of scope for this plugin).
4. CLI still exits 0 when the export fails (the pass logs the error but jadx's decompile result is independent); a strict-mode option could rethrow for CI pipelines.
5. ~~Record the jadx version in `meta_information`~~ — DONE: `architecture_name` is `dalvik-jadx-<version>` (Meta is display-only for BinDiff, so the mismatch shows up right in its UI/results DB).
6. ~~string_table/string_reference enrichment~~ — DROPPED for BinDiff: its reader never consumes string references (hardcoded `string_hash_=0` + TODO in `flow_graph.cc`), so this yields zero matching improvement; only worth doing if another BinExport2 consumer needs it. The former "biggest win" here was `raw_bytes`, which is now emitted.

## Reference URLs

- Proto + reference builder: `github.com/google/binexport` (`binexport2.proto`, `java/src/main/java/com/google/security/binexport/BinExport2Builder.java`).
- jadx plugin API: `jadx.api.plugins.JadxPlugin`, `.pass.types.JadxAfterLoadPass`, `jadx.core.ProcessClass` (the unload trap), `jadx.core.dex.nodes.{ClassNode,MethodNode,BlockNode,InsnNode}`.
