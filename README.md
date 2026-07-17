# jadx-binexport — jadx → BinExport plugin

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
| `BlockNode` | `BasicBlock` (global instruction-index ranges), enter block emitted first |
| `BlockNode.getSuccessors()` | `FlowGraph.Edge` (IF true/false from the `IfNode`, dominator-based back edges) |
| `InsnNode` | `Instruction` + `Operand`/`Expression` + canonical `raw_bytes` |
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
# -> build/libs/jadx-binexport-0.1.0.jar
```

The jar is self-contained (protobuf runtime is shaded and relocated).

## Install into jadx

```bash
jadx plugins --install-jar build/libs/jadx-binexport-0.1.0.jar
jadx plugins --list        # "jadx-binexport" should be listed
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
jadx -d out app.apk -Pjadx-binexport.output=/path/app.BinExport
# or a directory:  -Pjadx-binexport.outdir=/some/dir
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
/ unmatched functions plus call-graph and flow-graph visualizations. Or from the CLI:

```bash
bindiff app-v1.BinExport app-v2.BinExport --output_dir results/
```

### Diff and browse inside jadx (like the IDA BinDiff plugin)

You don't have to run `bindiff` by hand. A diff is always made from two
`.BinExport`s — and jadx already holds one of them (the open app), so you only
open the OTHER one. With app **A** open in jadx-gui and app **B** already
exported to `B.BinExport`:

**Plugins → Open BinExport (.BinExport)…** → pick `B.BinExport`.

The plugin exports A, runs `bindiff` on A vs B, and opens the results in one step.
A table lists every matched function that belongs to the open app (A), sorted by
similarity (changed functions first) and colored red→green. **Double-click a row
(or press Enter) to open that method in jadx.** A filter box narrows the list.

This needs the `bindiff` executable; if it's not on `PATH`, set its full path in
the `jadx-binexport.bindiff` plugin option.

Matches are linked by name (each method's full signature is written as
`mangled_name`), so navigation works despite the synthetic addresses — just diff
in a jadx session opened on the same app + jadx version that produced the exports.

## Works through obfuscation

The whole point is diffing app versions whose symbols are renamed (ProGuard/R8),
where names carry no signal. BinDiff matches on structure (call graph + CFG) and
on `Instruction.raw_bytes` — a canonical, file-independent rendering of each
instruction (mnemonic + operands + wrapped invokes) that this plugin emits so
BinDiff's content-hash matchers work. On a benchmark of one app obfuscated twice
with **disjoint R8 name dictionaries** (so every method name differs), matches
scored against R8's `mapping.txt` were **96% correct**; dropping `raw_bytes` in a
control run collapsed that to 23%, so it is load-bearing, not cosmetic.

## Important

- **Export both sides with the same jadx version.** jadx emits a normalized IR (not
  raw smali) and folds many `invoke`s into operand trees; the mnemonic set and
  folding differ across jadx versions, so mixing versions degrades matching. The
  jadx version is recorded in the file's `architecture_name` (`dalvik-jadx-<ver>`)
  so a mismatch is visible in BinDiff.
- **Dalvik (DEX) only.** Native `lib/*.so` files are ELF/ARM and already diff with
  the upstream BinExport ARM/AArch64 exporters — they are out of scope here.

## Status

**Verified end-to-end** (BinDiff 8):

- `./gradlew build` + an integration test (real jadx model → re-parsed BinExport2,
  invariants checked) pass.
- Real APK via jadx's `dex-input` path: a 1.3 MB APK exported to 13,226 functions /
  90,106 instructions with all structural invariants holding.
- BinDiff ingestion: the emitted files load and diff (13,226/13,226 self-match),
  and the obfuscated-rename benchmark above scores 96% correct.
- Deterministic: two exports of the same input are byte-identical (minus timestamp).

An opt-in smoke test runs the real-APK path:

```bash
./gradlew test -Pbinexport.smoke.apk=/path/to/app.apk
```

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
