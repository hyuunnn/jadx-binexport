# apk-diff — jadx → BinExport plugin

[한국어 README](README_ko.md)

A [jadx](https://github.com/skylot/jadx) plugin that exports the decompiler's
analysis — classes, methods, control-flow graphs and the call graph — into the
**BinExport2** protobuf format consumed by [BinDiff](https://www.zynamics.com/bindiff.html).

This lets you **diff and visualize two APKs' Dalvik bytecode in BinDiff**, the
same way you would diff native binaries exported from IDA Pro / Ghidra / Binary Ninja.

## Why

BinDiff matches functions structurally (call graph + per-function CFG + instruction
features), so it is largely architecture-agnostic. The BinExport2 format was in
fact designed with DEX in mind — it already has a `kDex` architecture, a `Module`
message meaning a Java class, and a `Vertex.module_index`. jadx already computes
everything BinDiff needs (methods, basic blocks, dominators, call references), with
strong Android/Dalvik understanding, name recovery and multidex handling. This
plugin is the missing glue: it serializes jadx's model into `.BinExport`.

## How it maps

| jadx | BinExport2 |
| --- | --- |
| `ClassNode` | `Module` (per-class, referenced by `Vertex.module_index`) |
| `MethodNode` | `CallGraph.Vertex` (sorted by ascending address) |
| `invoke` (incl. inlined/wrapped) | `CallGraph.Edge` + `Instruction.call_target` |
| `BlockNode` | `BasicBlock` (global instruction-index ranges) |
| `BlockNode.getSuccessors()` | `FlowGraph.Edge` (IF/SWITCH types, dominator-based back edges) |
| `InsnNode` | `Instruction` + `Operand`/`Expression` (register / immediate / symbol) |
| sorted method index | synthetic stable `uint64` address `(idx+1)<<20 + seq` |

Dalvik has no linear address space, so each method gets a stable synthetic base
address derived from a deterministic ordering of method signatures. Addresses only
need to be unique and consistent *within a single file* (BinDiff matches
structurally), and stay stable across rebuilds of the same input.

## Requirements

- jadx **1.5.6** (plugin API); it runs on any jadx-supported JRE (Java 11+).
- BinDiff (to consume the exported files).
- To build: a JDK (11+). The Gradle wrapper is included.

## Build

```bash
./gradlew build
# -> build/libs/apk-diff-binexport-0.1.0.jar
```

The jar is self-contained (protobuf runtime is shaded and relocated).

## Install into jadx

```bash
jadx plugins --install-jar build/libs/apk-diff-binexport-0.1.0.jar
jadx plugins --list        # "apk-diff-binexport" should be listed
```

In jadx-gui: *Preferences → Plugins → Install plugin (jar)*.

## Usage

### CLI (automatic)

Once inputs are loaded the plugin exports automatically:

```bash
# Export the two versions you want to compare — use the SAME jadx version for both.
jadx -d out_v1 app-v1.apk        # -> out_v1/app-v1.BinExport
jadx -d out_v2 app-v2.apk        # -> out_v2/app-v2.BinExport
```

Override the output path if needed (plugin options, also visible in
`jadx plugins` and the GUI preferences):

```bash
jadx -d out app.apk -Papk-diff-binexport.output=/path/app.BinExport
# or a directory:  -Papk-diff-binexport.outdir=/some/dir
# legacy system properties still work (jadx has no -J passthrough, use env vars):
#   JADX_OPTS="-Dbinexport.output=/path/app.BinExport" jadx -d out app.apk
```

Output path resolution (first match wins): `output` option → `outdir` option
→ jadx output dir, with filename `<input-basename>.BinExport`. When exporting two
versions with the same file name, use distinct paths — an existing file is
overwritten (with a warning in the log).

### GUI (on demand)

Open the APK, then **Plugins → Export to BinExport (.BinExport)**.

### Diff in BinDiff

Open BinDiff → **New Diff** → pick the two `.BinExport` files → run. You get matched
/ unmatched functions plus call-graph and flow-graph visualizations.

## Important

- **Export both sides with the same jadx version.** jadx emits a normalized IR (not
  raw smali) and folds many `invoke`s into operand trees; the mnemonic set and
  folding differ across jadx versions, so mixing versions degrades matching.
- **Dalvik (DEX) only.** Native `lib/*.so` files are ELF/ARM and already diff with
  the upstream BinExport ARM/AArch64 exporters — they are out of scope here.

## Status

Verified: `./gradlew build` and an end-to-end integration test (real jadx model →
re-parsed BinExport2, invariants checked) pass.

Not yet verified: a real APK via jadx's `dex-input` path, and BinDiff actually
ingesting the produced files (no BinDiff was available during development). The
mapping is input-agnostic (it operates on the post-load jadx model), so the DEX
path is expected to work, but a real APK smoke test is still pending.

## Development

See [CLAUDE.md](CLAUDE.md) for architecture notes, hard-won jadx gotchas (notably:
`cls.decompile()` unloads and discards the CFG — use `forceProcess` instead),
BinExport2 format rules, and the backlog.

```bash
./gradlew test        # run the integration test only
```

## License

The vendored `src/main/proto/binexport2.proto` is Apache-2.0 (© Google LLC), taken
verbatim from [google/binexport](https://github.com/google/binexport).
