# port-lint Proposed Changes

**Generated:** 2026-05-07
**Source:** tmp/regex-syntax/src
**Target:** src

These are review proposals only. They are emitted when a Rust -> Kotlin pair matches only after fallback normalization, so the existing `port-lint` header is not an exact provenance match.

| Target file | Current header | Proposed header | Source path | Reason |
|-------------|----------------|-----------------|-------------|--------|
| `commonMain/kotlin/io/github/kotlinmania/regexsyntax/hir/translate/Translate.kt` | `// port-lint: source src/hir/translate.rs` | `// port-lint: source hir/translate.rs` | `hir/translate.rs` | `port-lint provenance header matched only after fallback normalization: 'src/hir/translate.rs' vs expected 'hir/translate.rs'` |
| `commonMain/kotlin/io/github/kotlinmania/regexsyntax/ast/parse/Parse.kt` | `// port-lint: source src/ast/parse.rs` | `// port-lint: source ast/parse.rs` | `ast/parse.rs` | `port-lint provenance header matched only after fallback normalization: 'src/ast/parse.rs' vs expected 'ast/parse.rs'` |
| `commonTest/kotlin/io/github/kotlinmania/regexsyntax/ast/parse/ParseTest.kt` | `// port-lint: source src/ast/parse.rs` | `// port-lint: source ast/parse.rs` | `ast/parse.rs` | `port-lint provenance header matched only after fallback normalization: 'src/ast/parse.rs' vs expected 'ast/parse.rs'` |
| `commonMain/kotlin/io/github/kotlinmania/regexsyntax/hir/Mod.kt` | `// port-lint: source src/hir/mod.rs` | `// port-lint: source hir/mod.rs` | `hir/mod.rs` | `port-lint provenance header matched only after fallback normalization: 'src/hir/mod.rs' vs expected 'hir/mod.rs'` |
| `commonMain/kotlin/io/github/kotlinmania/regexsyntax/hir/print/Print.kt` | `// port-lint: source src/hir/print.rs` | `// port-lint: source hir/print.rs` | `hir/print.rs` | `port-lint provenance header matched only after fallback normalization: 'src/hir/print.rs' vs expected 'hir/print.rs'` |
| `commonMain/kotlin/io/github/kotlinmania/regexsyntax/hir/interval/Interval.kt` | `// port-lint: source src/hir/interval.rs` | `// port-lint: source hir/interval.rs` | `hir/interval.rs` | `port-lint provenance header matched only after fallback normalization: 'src/hir/interval.rs' vs expected 'hir/interval.rs'` |
| `commonMain/kotlin/io/github/kotlinmania/regexsyntax/ast/Mod.kt` | `// port-lint: source src/ast/mod.rs` | `// port-lint: source ast/mod.rs` | `ast/mod.rs` | `port-lint provenance header matched only after fallback normalization: 'src/ast/mod.rs' vs expected 'ast/mod.rs'` |
| `commonMain/kotlin/io/github/kotlinmania/regexsyntax/Error.kt` | `// port-lint: source src/error.rs` | `// port-lint: source error.rs` | `error.rs` | `port-lint provenance header matched only after fallback normalization: 'src/error.rs' vs expected 'error.rs'` |
| `commonMain/kotlin/io/github/kotlinmania/regexsyntax/ast/visitor/Visitor.kt` | `// port-lint: source src/ast/visitor.rs` | `// port-lint: source ast/visitor.rs` | `ast/visitor.rs` | `port-lint provenance header matched only after fallback normalization: 'src/ast/visitor.rs' vs expected 'ast/visitor.rs'` |
| `commonMain/kotlin/io/github/kotlinmania/regexsyntax/hir/visitor/Visitor.kt` | `// port-lint: source src/hir/visitor.rs` | `// port-lint: source hir/visitor.rs` | `hir/visitor.rs` | `port-lint provenance header matched only after fallback normalization: 'src/hir/visitor.rs' vs expected 'hir/visitor.rs'` |
| `commonMain/kotlin/io/github/kotlinmania/regexsyntax/CrateDoc.kt` | `// port-lint: source src/lib.rs` | `// port-lint: source lib.rs` | `lib.rs` | `port-lint provenance header matched only after fallback normalization: 'src/lib.rs' vs expected 'lib.rs'` |
| `commonMain/kotlin/io/github/kotlinmania/regexsyntax/MetaCharacter.kt` | `// port-lint: source src/lib.rs` | `// port-lint: source lib.rs` | `lib.rs` | `port-lint provenance header matched only after fallback normalization: 'src/lib.rs' vs expected 'lib.rs'` |
| `commonMain/kotlin/io/github/kotlinmania/regexsyntax/Parse.kt` | `// port-lint: source src/lib.rs` | `// port-lint: source lib.rs` | `lib.rs` | `port-lint provenance header matched only after fallback normalization: 'src/lib.rs' vs expected 'lib.rs'` |
| `commonMain/kotlin/io/github/kotlinmania/regexsyntax/debug/Debug.kt` | `// port-lint: source src/debug.rs` | `// port-lint: source debug.rs` | `debug.rs` | `port-lint provenance header matched only after fallback normalization: 'src/debug.rs' vs expected 'debug.rs'` |
| `commonMain/kotlin/io/github/kotlinmania/regexsyntax/either/Either.kt` | `// port-lint: source src/either.rs` | `// port-lint: source either.rs` | `either.rs` | `port-lint provenance header matched only after fallback normalization: 'src/either.rs' vs expected 'either.rs'` |
| `commonMain/kotlin/io/github/kotlinmania/regexsyntax/unicodetables/Mod.kt` | `// port-lint: source src/unicode_tables/mod.rs` | `// port-lint: source unicode_tables/mod.rs` | `unicode_tables/mod.rs` | `port-lint provenance header matched only after fallback normalization: 'src/unicode_tables/mod.rs' vs expected 'unicode_tables/mod.rs'` |
