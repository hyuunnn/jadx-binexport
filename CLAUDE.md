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
  BinExportPlugin.java   # JadxPlugin. CLI/library => auto (AfterLoadPass); GUI => menu action
  BinExportPass.java     # JadxAfterLoadPass — runs at end of load(), drives export
  Exporter.java          # THE mapping logic (jadx model -> BinExport2)
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
Output path resolution (first wins): `-Dbinexport.output=<file>` → `-Dbinexport.outdir=<dir>` → jadx out dir. GUI: `Plugins → Export to BinExport`.

## GOTCHAS (hard-won — read before touching Exporter)

1. **`cls.decompile()` DESTROYS the CFG.** `ProcessClass.process(cls, codegen=true)` calls `cls.unload()` right after code generation, nulling `MethodNode.blocks`. Symptom: export runs but `instructions=0`, empty flow graphs. Fix in use: `cls.root().getProcessClasses().forceProcess(cls)` runs the decompile passes (block split, SSA, dominators, region maker) **without** codegen/unload, leaving blocks intact and state `PROCESS_COMPLETE`. Normal `jadx.save()` afterwards still works (codegen from the already-processed state).

2. **jadx has no "after decompile" hook.** Pass types are only `JadxPreparePass`, `JadxDecompilePass`, `JadxAfterLoadPass`. `JadxAfterLoadPass.init(decompiler)` fires at the *end of `load()`*, **before** any decompilation. So the pass must drive decompilation itself (that's what `forceProcess` above is for) and then write the file. There is no streaming "last method" signal — the after-load-drives-everything design gives a clean single-threaded pass with a definite end.

3. **GUI vs CLI branching.** `context.getGuiContext() != null` ⇒ GUI: register an on-demand menu action (don't auto-export the whole app on every project open). Else CLI/library: register the AfterLoadPass. Both call `Exporter.run(decompiler)`.

4. **jadx IR is normalized, NOT raw smali.** Instructions are jadx's post-transform IR (`InsnType`), and many `invoke`s get **folded into operand trees** as `InsnWrapArg`. Consequences:
   - Call-graph completeness requires recursing wrapped args (`arg.isInsnWrap()` → `arg.unwrap()`), else you miss calls. `Exporter.collectCallees` does this.
   - Two files are only comparable in BinDiff if produced by the **same jadx version** (mnemonic set + folding differ across versions). This is a hard rule, surface it to users.

5. **Empty/synthetic blocks.** jadx enter/exit blocks often have 0 instructions. A `BasicBlock` needs ≥1 instruction for its index range, so we emit a synthetic `nop` for empty blocks (kept in the mnemonic histogram too). Don't "optimize" these away or CFG edges break.

## BinExport2 format rules (from the canonical reference)

Canonical reference is Google's Ghidra exporter `java/src/main/java/com/google/security/binexport/BinExport2Builder.java` in `google/binexport`. Mirror it, not guesses.

- **FlowGraph indices are GLOBAL.** `entry_basic_block_index`, `basic_block_index`, and edge `source/target_basic_block_index` all index into the top-level `BinExport2.basic_block` array — NOT into the flow graph's local list.
- **Mnemonic index 0 = most frequent** (build a histogram, sort desc by count then name). Omit `mnemonic_index` when it's 0.
- **Vertices MUST be sorted by ascending `address`** (BinDiff requirement). We add them in method-index order and give method `i` address `(i+1) << 20`, so ordering is automatic; `vertexIndex == methodIndex`.
- `Instruction.address` is optional (fill only on discontinuity); we fill it on **every** instruction (simpler, valid).
- `Expression` default type is `IMMEDIATE_INT` → leave `type` unset for immediates (matches "omit defaults" intent).
- De-dup `Expression`/`Operand` via `HashMap<proto,Integer>` (proto message equality). Instructions/blocks are NOT deduped — we emit them method-by-method so each block's instructions are a contiguous `[begin,end)` range.

## Address synthesis

Dalvik has no linear address space. Scheme: sort methods by `MethodInfo.getRawFullId()`, method `i` → base `(i+1) << 20`; instruction address = `base + sequenceIndex`. `1<<20` (~1M) headroom per method. Addresses only need to be **unique + stable within one file** (BinDiff matches structurally, not by absolute address); stable ordering keeps the same method at the same address across rebuilds of the same input.

## Key jadx API cheat-sheet

- `JadxDecompiler.getClasses()` → `List<JavaClass>`; `jc.getClassNode()` → `ClassNode` (marked internal but public).
- `ClassNode`: `root()`, `getMethods()`, `getInnerClasses()`, `getInlinedClasses()`, `getClassInfo().getFullName()`.
- `MethodNode`: `getMethodInfo()`, `isNoCode()`, `getBasicBlocks()` (null/empty until processed), `getEnterBlock()`, `getParentClass()`.
- `MethodInfo`: `getRawFullId()` (stable, `$`-separated inner), `getFullId()` (readable), `getShortId()`.
- `BlockNode`: `getInstructions()`, `getSuccessors()`, `getPos()`, `getDoms()` (BitSet) — back edge ⇔ target dominates source: `from.getDoms().get(to.getPos())`.
- `InsnNode`: `getType()` (`InsnType`), `getResult()`, `getArguments()`, `getOffset()`.
- Calls: `insn instanceof BaseInvokeNode` → `getCallMth()` (`MethodInfo`). Args: `InsnArg.isLiteral()/isRegister()/isInsnWrap()`, `LiteralArg.getLiteral()`, `RegisterArg.getRegNum()`.

## Test approach

`ExporterIntegrationTest` compiles a small class with `ToolProvider` javac, loads it through jadx via **`jadx-java-input`** (compiled `.class` path, not DEX), lets the AfterLoadPass export, then `BinExport2.parseFrom` and asserts invariants (sorted vertices, entry∈blocks, global indices in range, valid instruction ranges, ≥1 call edge, a back edge from the loop). Last run: `vertices=4 edges=2 instructions=30 basicBlocks=25 flowGraphs=4 backEdge=true`.

## NOT yet verified (do this next)

- **Real APK/DEX path** (`jadx-dex-input`). The mapping is input-agnostic (operates on the post-load jadx model) so it should work, but no real APK smoke test has been run in-repo.
- **BinDiff actually ingesting the file.** The proto is structurally valid and follows the reference builder, but no BinDiff was available to confirm end-to-end diffing.

## Backlog / next improvements

1. Enrich operands: put const-string values, field/type/method symbols into `string_table` + `string_reference` (currently operands are just reg/immediate/`symbol`). Biggest matching-quality win.
2. Add `IMPORTED` vertices + call edges for framework/external calls (currently only in-app edges).
3. IF true/false edge labeling is approximate (first succ = TRUE). Derive real branch target from the `if` insn if precision matters.
4. Memory: all classes stay loaded (no unload) — fine for small/medium apps, may need streaming for huge multidex.
5. Consider exporting native `lib/*.so` via existing BinExport ARM/AArch64 path for a full "APK diff" (out of scope for this plugin).

## Reference URLs

- Proto + reference builder: `github.com/google/binexport` (`binexport2.proto`, `java/src/main/java/com/google/security/binexport/BinExport2Builder.java`).
- jadx plugin API: `jadx.api.plugins.JadxPlugin`, `.pass.types.JadxAfterLoadPass`, `jadx.core.ProcessClass` (the unload trap), `jadx.core.dex.nodes.{ClassNode,MethodNode,BlockNode,InsnNode}`.
