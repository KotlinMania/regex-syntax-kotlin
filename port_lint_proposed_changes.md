# port-lint Proposed Changes

**Generated:** 2026-05-03
**Source:** tmp/regex-syntax/src/parser.rs
**Target:** src/commonMain/kotlin/io/github/kotlinmania/regexsyntax/parser/Parser.kt

These are review proposals only. They are emitted when a Rust -> Kotlin pair matches only after fallback normalization, so the existing `port-lint` header is not an exact provenance match.

| Target file | Current header | Proposed header | Source path | Reason |
|-------------|----------------|-----------------|-------------|--------|
| `src/commonMain/kotlin/io/github/kotlinmania/regexsyntax/parser/Parser.kt` | `// port-lint: source src/parser.rs` | `// port-lint: source parser.rs` | `parser.rs` | `port-lint provenance header matched only after fallback normalization: 'src/parser.rs' vs expected 'parser.rs'` |
