# regex-syntax-kotlin — agent guide

This repo is the Kotlin Multiplatform port of upstream Rust's [`regex-syntax`](https://github.com/rust-lang/regex/tree/master/regex-syntax) crate. Upstream source lives at `tmp/regex-syntax/` and is the **read-only** translation oracle. Never edit `tmp/`.

## Scope

`regex-syntax` is the regex parser and AST/HIR for Rust's regex flavour — what produces `Hir` and `Ast` values from a pattern source string. It does **not** perform matching; that's `regex-automata`. Match callers go through `Hir` once parsed.

This Kotlin port covers the parsing pipeline and the AST/HIR types. No matching engine.

## Maven coordinates

`io.github.kotlinmania:regex-syntax:<version>`

Package root: `io.github.kotlinmania.regexsyntax`. Subpackages mirror the upstream Rust module tree (e.g. `regex-syntax/src/hir/print.rs` → `io.github.kotlinmania.regexsyntax.hir.print`).

## Port-lint headers

```kotlin
// port-lint: source <path-relative-to-tmp/regex-syntax/>
package io.github.kotlinmania.regexsyntax.<module>
```

## Translation discipline

Line-by-line transliteration. Read the Rust file end to end, then port. Don't reorder, summarize, or "improve."

- **Doc comments translate word-for-word.** Rust syntax inside KDoc gets rewritten to Kotlin equivalents.
- **No no-op shells.** Rust constructs the GC subsumes (`Box<T>`, `Arc<T>`, `Cell<T>`, `Rc<T>`, `Pin`, `mem::forget`, `drop_in_place`, `MaybeUninit`, `dyn Trait`) get **deleted** in the port.
- **No `mod.rs` → `Mod.kt`.** Re-home implementation, rewire callers.

## Code discipline

- **No `@Suppress`.** Warnings are errors.
- **No stubs.** No `TODO()`, no `error("not implemented")`, no empty class bodies on types that have fields and methods.
- **No JVM imports.** Pure Kotlin Multiplatform.
- **No synthetic typealiases for ergonomics.**

## Blast radius

- No repo-wide scripting.

## Verification

```bash
./gradlew test
./gradlew macosArm64Test
./gradlew linuxX64Test
./gradlew jsNodeTest
./gradlew wasmJsNodeTest
```

`./gradlew jvmTest` is **not** valid — there is no JVM target.

## Approved dependencies

- `kotlinx-coroutines-core`
- `kotlinx-serialization-core`, `kotlinx-serialization-json`
- `kotlinx-collections-immutable`

## Dependents

- `logos-kotlin` — uses `Hir` to model regex patterns.
- (Future) any Kotlin port of `regex-automata` or another `regex-syntax`-consuming crate.

## Subagent policy

Do not delegate `.kt` writes to subagents. Search and read-only reports via subagents are fine.

## Commit style

No AI branding, no Co-Authored-By lines, no emoji. One file → one commit.
