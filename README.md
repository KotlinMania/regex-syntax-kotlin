# regex-syntax-kotlin

Kotlin Multiplatform port of the [rust-lang/regex](https://github.com/rust-lang/regex) `regex-syntax` crate — the parser and AST/HIR for Rust's regex flavour.

`regex-syntax` is the foundational regex parsing library used by `regex` and `regex-automata` upstream, and (by extension) by anything in the Rust ecosystem that needs to introspect regex source. Logos uses it via `regex_syntax::Hir` to compile per-token regex annotations into a unified state machine.

## Maven coordinates

```kotlin
dependencies {
    implementation("io.github.kotlinmania:regex-syntax:0.1.0")
}
```

## Why a separate artifact?

This artifact is consumed by:

- `logos-kotlin` — uses `Hir` to model regex patterns when compiling a lexer state machine.
- (Future) any Kotlin port of a Rust crate that builds on `regex_syntax` (e.g. `regex_automata`).

Separating it out matches the upstream Cargo split and keeps the regex-parsing layer reusable.

## Targets

Kotlin Multiplatform, no JVM-only target:

- `macosArm64`, `macosX64`
- `linuxX64`
- `mingwX64`
- `iosArm64`, `iosX64`, `iosSimulatorArm64`
- `js` (browser + nodejs)
- `wasmJs` (browser + nodejs)
- `androidLibrary`

## Status

**Phase 1: scaffolding.** Repository stood up; source files are being ported file-by-file from `tmp/regex-syntax/` (a fresh clone from `rust-lang/regex`). Public API target is `Hir`, `HirKind`, `ParserBuilder`, and the lower-level `Ast` types.

## License

Dual-licensed Apache-2.0 / MIT, matching upstream. See [LICENSE](./LICENSE).
