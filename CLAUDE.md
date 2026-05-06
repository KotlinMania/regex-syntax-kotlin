# Claude Code Project Instructions — regex-syntax-kotlin

## Project Overview

Kotlin Multiplatform port of [`rust-lang/regex/regex-syntax`](https://github.com/rust-lang/regex/tree/master/regex-syntax). `regex-syntax` is the regex parser + AST/HIR layer used by the Rust regex ecosystem. It produces typed `Ast` and `Hir` values from a pattern source string and does no matching.

Upstream Rust source lives at `tmp/regex-syntax/` and is the read-only translation oracle. **Never edit `tmp/`.**

## Translator's mindset

This is a translation project, not a software-engineering project. While porting a file, you are
the Kotlin author of the same document a Rust author wrote. Architecture, optimization, design
critique, drift measurement — all later. While translating, the only job is the translation.

The discipline:

1. **Read the whole upstream file before you type.** A line-by-line port composes only when you
   know how the file ends. If the file is too long to read in one sitting, split your turn into
   "read the file" and "write the file" — never start typing on a file you've only half-read.

2. **One Rust file → one Kotlin file. Always.** No splitting one `.rs` across several `.kt`. No
   merging several `.rs` into one `.kt`. The 1:1 mapping is the contract; everything downstream
   (ast_distance, port-lint headers, code review) assumes it. If a `.rs` is genuinely too big for
   one Kotlin file, that's a sign you're in `mod.rs`-equivalent territory and the upstream itself
   is a re-export — verify, don't split.

3. **Translate top to bottom in upstream order.** Preserve the declaration order. Don't reorder
   for "logical flow" — the upstream's order *is* the logical flow. The reader who already knows
   the Rust file should be able to scroll the Kotlin file and find every item in the same place.

4. **Comments are content.** License header, module-level doc, every `///` block, every inline
   `//` note, every upstream `// TODO`/`// FIXME` — all translate. Rust syntax inside doc comments
   gets rewritten to Kotlin equivalents (`Vec<T>` → `List<T>`, `Self::foo()` → `foo()`, lifetimes
   dropped, `cfg(test)` and `#[derive(...)]` lifted into prose). You are translating a *document*,
   not just the code.

5. **When a Rust idiom has no Kotlin analog, apply the mapping rule and move on.** `Box<T>`,
   `Arc<T>`, `Cell<T>`, `RefCell<T>`, `Rc<T>`, lifetimes, `PhantomData`, `mem::forget`,
   `drop_in_place`, `Pin`, `MaybeUninit`, `dyn Trait` — all collapse per the mapping table.
   Don't relitigate. A proc-macro becomes a builder/runtime API, not nothing. An upstream Rust
   crate with no KMP equivalent becomes a *separate Kotlin port*, not a `// TODO` placeholder.
   Pay the snowball cost upfront — the next consumer will thank you.

6. **Don't measure mid-port.** ast_distance, FnSim, similarity reports — useful *after* a file is
   done, useless *during*. Mid-translation measurement is procrastination dressed as rigor. Run
   the tools when a file lands or when a port phase wraps, not while you're choosing between
   `Result<T>` and `T?`.

7. **Don't optimize the translation.** "This Kotlin shape would be simpler" is the wrong
   thought. The upstream shape is the spec. If a faithful translation produces a function that
   takes a parameter you'd never write in Kotlin from scratch, take it. Optimization is a
   separate, named pass after parity is reached — never blended into the translation.

8. **Don't re-architect mid-port.** "This whole module would be cleaner if..." — write the
   thought on a sticky note, throw the sticky note away, finish the file. The current architecture
   is the upstream's architecture. Earn the right to redesign by first reaching parity.

9. **Compile errors during translation are normal and expected.** A bottom-of-tree file compiles
   when its deps are ported, not before. Don't pause to "make it compile" mid-port — that pulls
   you into stub-shaped fixes that you'll have to undo. Climb the dep tree bottom-up; the leaves
   compile first, then their parents, then everything compiles together at the end.

10. **Bottom-up always.** Port dependencies before consumers. The order isn't optional; trying to
    port top-down produces a tree of stubs that all need replacing.

11. **Hard files are not skippable.** When you hit one, port it. Skipping leaves a `// TODO`-shaped
    hole that grows every time another consumer needs it.

12. **Warnings are real, but `@Suppress` is never the answer.** `UNUSED_PARAMETER` on a callback
    helper means the function shape doesn't fit Kotlin — restructure the signature, don't suppress.

13. **Stop at file boundaries, not function boundaries.** After every completed file, exhale,
    commit, move on.

14. **Doc-port discipline applies even when the upstream doc is awkward.** Translate the tortured
    sentence. Don't smooth it.

15. **The cheat detector is your friend.** Rust syntax in Kotlin source — code or comments — is
    the cheat we're catching.

The sticky-note version: **"Read the file. Translate it. Don't think about anything else."**

## Project Goals (the contract)

1. **Functional parity with upstream.** Every public type and function in `regex-syntax/src/` has
   a Kotlin counterpart that behaves identically against the same fixtures.
2. **All tests pass** on every shipped target.
3. **Kotlin source looks like Kotlin source.** No carried-over Rust idioms in code, KDoc, or API.
4. **No hacks.** No stubs, no `TODO()`, no `FIXME`, no `@Suppress`, no JVM imports.

## Verification

```bash
./gradlew test
./gradlew macosArm64Test
./gradlew linuxX64Test
./gradlew jsNodeTest
./gradlew wasmJsNodeTest
```

## Targets — Kotlin Multiplatform, no JVM

- `macosArm64`
- `linuxX64`, `mingwX64`
- `iosArm64`, `iosSimulatorArm64`
- `js`, `wasmJs`, `androidLibrary`

### Forbidden imports

- `import kotlin.jvm.*`
- any `import java.*`
- any `import javax.*`

### Approved dependencies

- `kotlinx-coroutines-core`
- `kotlinx-serialization-core`, `kotlinx-serialization-json`
- `kotlinx-collections-immutable`

## Naming Conventions

| Kind | Form |
|---|---|
| Functions, parameters, locals | `camelCase` |
| Classes, data classes, sealed types | `PascalCase` |
| Interfaces | `PascalCase`, no `I` prefix |
| `const val`, `enum` entries | `SCREAMING_SNAKE_CASE` permitted |
| Type parameters | `T`, `K`, `V` (single uppercase) |
| Packages | all lowercase, no camelCase |

## Port-lint headers (REQUIRED)

```kotlin
// port-lint: source <path-relative-to-tmp/regex-syntax/>
package io.github.kotlinmania.regexsyntax.<module>
```

## File Organization

```
src/
├── commonMain/kotlin/io/github/kotlinmania/regexsyntax/
│   ├── (top-level types: Error, Position, Span, etc.)
│   ├── ast/      # ast.rs and ast/{parse,print,visitor}.rs
│   ├── hir/      # hir.rs and hir/{print,translate,literal,interval}.rs
│   ├── unicode/  # unicode.rs and unicode tables
│   └── ...
└── commonTest/
    └── kotlin/io/github/kotlinmania/regexsyntax/
```

## Cross-Project Coordination

Downstream consumers (Maven, never include-build):

- `logos-kotlin` — uses `Hir` for compiling regex patterns.

## CI

```bash
gh run list --workflow ci.yml --limit 5
gh pr checks <pr-number>
```

## Commit Messages

- No AI branding or attribution.
- Clear, descriptive, focused on what changed and why.
- No "Co-Authored-By" lines.
- No emoji or robot references.

## Re-exports from upstream `mod.rs` files

When an upstream Rust `mod.rs` is **only re-exporting** something that actually lives elsewhere
(`pub use <crate-path>::<Name>;`, often under a different name), do **not** preserve that
re-export shape in Kotlin as a "central alias" API. Do not write a `typealias` for the
re-exported name. The existing `Forbidden` rule against "Re-export typealias files at root
packages" is enforced through this procedure.

Workflow:

1. **Identify what the `mod.rs` is re-exporting and the name it's exported as.** Record both
   the original symbol's fully-qualified upstream path and the (possibly different) re-export
   name.

2. **Find callers — Rust-side first, then Kotlin-side.** Many `*-kotlin` repos are
   bootstrap-only (`tmp/` cloned, little or no Kotlin ported yet), so the deterministic source
   of truth is the Rust import graph, not the Kotlin source. Grepping the Kotlin tree first
   will silently miss every caller whose port hasn't started.

   a. **Rust-side (deterministic, primary).** Build or query a graph (graphml or an equivalent
      JSON index) of every `use` statement and every `pub use` re-export across all
      `tmp/<crate>/**/*.rs` files in the workspace, keyed by symbol path. Every Rust crate that
      does `use <reexport-crate>::<reexport-path>::<Name>` — directly, or via a transitive
      `pub use` chain — is a future Kotlin caller. For each importer, drill into the Rust
      source to find the specific call sites: `<Name>(…)`, `: <Name>`, `<Name>::method`,
      `impl <Name> for …`, pattern matches, trait bounds, generics. Record the Rust path of
      each call site so that when that crate is later ported to Kotlin, the translation lands
      on the upstream symbol from day one and never on the re-export.

   b. **Kotlin-side (live ports, secondary).** Repos that have already produced Kotlin source
      need migration *now*. Search `*-kotlin/src/**/*.kt` for:
      - direct imports: `import <reexport-package>.<Name>`
      - wildcard imports of the re-export package, when `<Name>` is used in the file body
      - fully-qualified inline references

   The Rust pass catches callers whose Kotlin doesn't exist yet; the Kotlin pass catches
   callers already ported. Both must run.

3. **Rewrite each live Kotlin caller to reference the upstream/original symbol directly.** If
   the caller still needs to write `<Name>` unchanged, use Kotlin aliasing:
   `import <upstream-fully-qualified-name> as <Name>`. Never bridge with a Kotlin `typealias`.
   For Rust-side findings whose Kotlin counterpart hasn't been written yet, no edit is made
   now — instead, the call sites are recorded as a porting hint for whoever lands the Kotlin
   translation later.

4. **Keep `Mod.kt` (or the equivalent file for that package) as a tracking file.** It carries
   the translated upstream module-level comments and a literal-quoted reference to each
   upstream `pub use` line (e.g. `// pub use crate::lib::result::Result;`). Each time a caller
   is migrated off the re-export, append the caller's absolute path under a
   `// Callers migrated:` ledger in `Mod.kt`. Append, never delete. Once all callers are
   migrated, the `typealias` (if any) is removed; the tracking file remains as the ledger of
   the migration.

   Also record the **Rust-side projected callers** (crates with `tmp/` that import the
   re-export but haven't been ported yet) under a `// Projected callers (Rust):` block in the
   same file, so future porters see the migration target before they ever introduce a new
   caller pointing at the re-export.

Reference example: `/Volumes/stuff/Projects/kotlinmania/serde-kotlin/tmp/serde/serde_core/src/private/mod.rs`
re-exports `Result` from `crate::lib::result`. The Kotlin tracking file lives at
`/Volumes/stuff/Projects/kotlinmania/serde-kotlin/src/commonMain/kotlin/io/github/kotlinmania/serde/core/private/Mod.kt`.
A caller that previously did `import io.github.kotlinmania.serde.core.private.Result` is
rewritten to `import kotlin.Result as Result` (or just removes the import and relies on the
auto-imported `kotlin.Result`).
