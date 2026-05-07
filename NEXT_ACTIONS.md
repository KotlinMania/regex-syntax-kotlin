# Immediate Actions - High-Value Files

Based on AST analysis, here are the concrete next steps.

## Summary

- **Files Present:** 33/33 (100.0%)
- **Function parity:** 619/739 matched (target 1168) — 83.8%
- **Class/type parity:** 119/134 matched (target 279) — 88.8%
- **Combined symbol parity:** 738/873 matched (target 1447) — 84.5%
- **Average inline-code cosine:** 0.43 (function body across 28 matched files)
- **Average documentation cosine:** 0.33 (doc text across 28 matched files)
- **Cheat-zeroed Files:** 19
- **Critical Issues:** 21 files with <0.60 function similarity

## Priority 1: Fix Incomplete High-Dependency Files

No incomplete high-dependency files detected.

## Priority 2: Port Missing High-Value Files

Critical missing files (>10 dependencies):

No missing high-value files detected.

## Detailed Work Items

Every matched file is listed below with function and type symbol parity.

### 1. unicode

- **Target:** `unicode.Unicode [ZERO]`
- **Similarity:** 0.00
- **Dependents:** 1
- **Priority Score:** 1034710.0
- **Functions:** 36/39 matched (target 50)
- **Missing functions:** `class`, `simple_fold_ok`, `contains_case_map`
- **Types:** 8/8 matched (target 18)
- **Missing types:** _none_
- **Tests:** 7/9 matched

### 2. parser

- **Target:** `parser.Parser`
- **Similarity:** 0.92
- **Dependents:** 1
- **Priority Score:** 1001600.8
- **Functions:** 14/14 matched (target 16)
- **Missing functions:** _none_
- **Types:** 2/2 matched
- **Missing types:** _none_

### 3. unicode_tables.age

- **Target:** `age.Age [ZERO]`
- **Similarity:** 0.00
- **Dependents:** 1
- **Priority Score:** 1000010.0
- **Functions:** 0/0 matched (target 2)
- **Missing functions:** _none_
- **Types:** 0/0 matched
- **Missing types:** _none_

### 4. unicode_tables.perl_word

- **Target:** `perlword.PerlWord [ZERO]`
- **Similarity:** 0.00
- **Dependents:** 1
- **Priority Score:** 1000010.0
- **Functions:** 0/0 matched (target 4)
- **Missing functions:** _none_
- **Types:** 0/0 matched
- **Missing types:** _none_

### 5. unicode_tables.property_values

- **Target:** `propertyvalues.PropertyValues`
- **Similarity:** 1.00
- **Dependents:** 1
- **Priority Score:** 1000000.0
- **Functions:** 0/0 matched
- **Missing functions:** _none_
- **Types:** 0/0 matched
- **Missing types:** _none_

### 6. unicode_tables.property_names

- **Target:** `propertynames.PropertyNames`
- **Similarity:** 1.00
- **Dependents:** 1
- **Priority Score:** 1000000.0
- **Functions:** 0/0 matched
- **Missing functions:** _none_
- **Types:** 0/0 matched
- **Missing types:** _none_

### 7. hir.translate

- **Target:** `translate.TranslateTest [ZERO] [PROVENANCE-FALLBACK]`
- **Similarity:** 0.00
- **Dependents:** 0
- **Priority Score:** 414910.0
- **Functions:** 103/140 matched (target 152)
- **Missing functions:** `eq`, `parse`, `t`, `t_err`, `t_bytes`, `props`, `props_bytes`, `hir_lit`, `hir_blit`, `hir_capture_name`, `hir_quest`, `hir_star`, `hir_plus`, `hir_range`, `hir_alt`, `hir_cat`, `hir_uclass_query`, `hir_uclass_perl_word`, `hir_ascii_uclass`, `hir_ascii_bclass`, `hir_uclass`, `hir_bclass`, `hir_case_fold`, `hir_negate`, `uclass`, `bclass`, `class_case_fold`, `class_negate`, `hir_union`, `hir_difference`, `hir_look`, `class_perl_word_disabled`, `class_perl_space_disabled`, `class_perl_digit_disabled`, `class_unicode_gencat_disabled`, `class_unicode_script_disabled`, `class_unicode_age_disabled`
- **Types:** 6/9 matched (target 18)
- **Missing types:** `Result`, `Output`, `Err`
- **Tests:** 46/83 matched
- **Provenance warning:** port-lint provenance header matched only after fallback normalization: `src/hir/translate.rs` vs expected `hir/translate.rs`
- **Proposed provenance header:** `// port-lint: source hir/translate.rs` (current: `// port-lint: source src/hir/translate.rs`)
- **Lint issues:** 1

### 8. ast.parse

- **Target:** `parse.Parse [PROVENANCE-FALLBACK]`
- **Similarity:** 0.72
- **Dependents:** 0
- **Priority Score:** 343302.8
- **Functions:** 92/122 matched (target 178)
- **Missing functions:** `eq`, `s`, `parser_octal`, `parser_empty_min_range`, `parser_nest_limit`, `parser_ignore_whitespace`, `nspan`, `npos`, `span_range`, `lit`, `meta_lit`, `lit_with`, `concat`, `concat_with`, `alt`, `group`, `flag_set`, `union`, `intersection`, `difference`, `symdifference`, `itemset`, `item_ascii`, `item_unicode`, `item_perl`, `item_bracket`, `empty`, `range`, `alnum`, `lower`
- **Types:** 8/11 matched (target 20)
- **Missing types:** `Result`, `Output`, `Err`
- **Tests:** 14/44 matched
- **Provenance warning:** port-lint provenance header matched only after fallback normalization: `src/ast/parse.rs` vs expected `ast/parse.rs`
- **Provenance warning:** port-lint provenance header matched only after fallback normalization: `src/ast/parse.rs` vs expected `ast/parse.rs`
- **Proposed provenance header:** `// port-lint: source ast/parse.rs` (current: `// port-lint: source src/ast/parse.rs`)
- **Proposed provenance header:** `// port-lint: source ast/parse.rs` (current: `// port-lint: source src/ast/parse.rs`)
- **Lint issues:** 5

### 9. hir.mod

- **Target:** `hir.HirTest [STUB] [PROVENANCE-FALLBACK]`
- **Similarity:** 0.00
- **Dependents:** 0
- **Priority Score:** 214510.0
- **Functions:** 105/123 matched (target 232)
- **Missing functions:** `class`, `drop`, `uclass`, `bclass`, `uranges`, `ucasefold`, `uunion`, `uintersect`, `udifference`, `usymdifference`, `unegate`, `branges`, `bcasefold`, `bunion`, `bintersect`, `bdifference`, `bsymdifference`, `bnegate`
- **Types:** 20/22 matched (target 44)
- **Missing types:** `Item`, `Bound`
- **Tests:** 21/37 matched
- **Provenance warning:** port-lint provenance header matched only after fallback normalization: `src/hir/mod.rs` vs expected `hir/mod.rs`
- **Proposed provenance header:** `// port-lint: source hir/mod.rs` (current: `// port-lint: source src/hir/mod.rs`)
- **Lint issues:** 1

### 10. hir.literal

- **Target:** `literal.Literal [STUB]`
- **Similarity:** 0.00
- **Dependents:** 0
- **Priority Score:** 139510.0
- **Functions:** 76/89 matched (target 122)
- **Missing functions:** `default`, `cross_preamble`, `fmt`, `from`, `as_ref`, `parse`, `prefixes`, `suffixes`, `e`, `I`, `seq`, `opt`, `class`
- **Types:** 6/6 matched (target 11)
- **Missing types:** _none_
- **Tests:** 14/22 matched

### 11. hir.print

- **Target:** `print.PrintTest [ZERO] [PROVENANCE-FALLBACK]`
- **Similarity:** 0.00
- **Dependents:** 0
- **Priority Score:** 62910.0
- **Functions:** 20/24 matched (target 29)
- **Missing functions:** `default`, `roundtrip`, `roundtrip_bytes`, `roundtrip_with`
- **Types:** 3/5 matched (target 4)
- **Missing types:** `Output`, `Err`
- **Tests:** 10/13 matched
- **Provenance warning:** port-lint provenance header matched only after fallback normalization: `src/hir/print.rs` vs expected `hir/print.rs`
- **Proposed provenance header:** `// port-lint: source hir/print.rs` (current: `// port-lint: source src/hir/print.rs`)
- **Lint issues:** 1

### 12. ast.print

- **Target:** `commonMain.kotlin.io.github.kotlinmania.regexsyntax.ast.print.Print [ZERO]`
- **Similarity:** 0.00
- **Dependents:** 0
- **Priority Score:** 54110.0
- **Functions:** 33/36 matched (target 39)
- **Missing functions:** `default`, `roundtrip`, `roundtrip_with`
- **Types:** 3/5 matched (target 4)
- **Missing types:** `Output`, `Err`
- **Tests:** 9/11 matched

### 13. utf8

- **Target:** `utf8.Utf8 [ZERO]`
- **Similarity:** 0.00
- **Dependents:** 0
- **Priority Score:** 52910.0
- **Functions:** 20/23 matched (target 43)
- **Missing functions:** `rutf8`, `never_accepts_surrogate_codepoints`, `encode_surrogate`
- **Types:** 4/6 matched (target 9)
- **Missing types:** `IntoIter`, `Item`
- **Tests:** 3/6 matched

### 14. hir.interval

- **Target:** `interval.Interval [PROVENANCE-FALLBACK]`
- **Similarity:** 0.59
- **Dependents:** 0
- **Priority Score:** 42804.1
- **Functions:** 20/23 matched (target 34)
- **Missing functions:** `eq`, `create`, `as_u32`
- **Types:** 4/5 matched (target 7)
- **Missing types:** `Item`
- **Provenance warning:** port-lint provenance header matched only after fallback normalization: `src/hir/interval.rs` vs expected `hir/interval.rs`
- **Proposed provenance header:** `// port-lint: source hir/interval.rs` (current: `// port-lint: source src/hir/interval.rs`)
- **Lint issues:** 1

### 15. ast.mod

- **Target:** `ast.AstTest [STUB] [PROVENANCE-FALLBACK]`
- **Similarity:** 0.00
- **Dependents:** 0
- **Priority Score:** 38710.0
- **Functions:** 43/46 matched (target 68)
- **Missing functions:** `arbitrary`, `size_hint`, `drop`
- **Types:** 41/41 matched (target 105)
- **Missing types:** _none_
- **Tests:** 2/2 matched
- **Provenance warning:** port-lint provenance header matched only after fallback normalization: `src/ast/mod.rs` vs expected `ast/mod.rs`
- **Proposed provenance header:** `// port-lint: source ast/mod.rs` (current: `// port-lint: source src/ast/mod.rs`)
- **Lint issues:** 1

### 16. error

- **Target:** `regexsyntax.ErrorTest [ZERO] [PROVENANCE-FALLBACK]`
- **Similarity:** 0.00
- **Dependents:** 0
- **Priority Score:** 21510.0
- **Functions:** 10/12 matched (target 19)
- **Missing functions:** `fmt`, `assert_panic_message`
- **Types:** 3/3 matched (target 6)
- **Missing types:** _none_
- **Tests:** 2/3 matched
- **Provenance warning:** port-lint provenance header matched only after fallback normalization: `src/error.rs` vs expected `error.rs`
- **Proposed provenance header:** `// port-lint: source error.rs` (current: `// port-lint: source src/error.rs`)
- **Lint issues:** 1

### 17. ast.visitor

- **Target:** `commonMain.kotlin.io.github.kotlinmania.regexsyntax.ast.visitor.Visitor [PROVENANCE-FALLBACK]`
- **Similarity:** 0.74
- **Dependents:** 0
- **Priority Score:** 12802.6
- **Functions:** 22/23 matched (target 26)
- **Missing functions:** `fmt`
- **Types:** 5/5 matched (target 15)
- **Missing types:** _none_
- **Provenance warning:** port-lint provenance header matched only after fallback normalization: `src/ast/visitor.rs` vs expected `ast/visitor.rs`
- **Proposed provenance header:** `// port-lint: source ast/visitor.rs` (current: `// port-lint: source src/ast/visitor.rs`)
- **Lint issues:** 1

### 18. hir.visitor

- **Target:** `visitor.Visitor [PROVENANCE-FALLBACK]`
- **Similarity:** 0.73
- **Dependents:** 0
- **Priority Score:** 1302.7
- **Functions:** 10/10 matched (target 11)
- **Missing functions:** _none_
- **Types:** 3/3 matched (target 7)
- **Missing types:** _none_
- **Provenance warning:** port-lint provenance header matched only after fallback normalization: `src/hir/visitor.rs` vs expected `hir/visitor.rs`
- **Proposed provenance header:** `// port-lint: source hir/visitor.rs` (current: `// port-lint: source src/hir/visitor.rs`)
- **Lint issues:** 1

### 19. lib

- **Target:** `regexsyntax.MetaCharacterTest [STUB] [PROVENANCE-FALLBACK]`
- **Similarity:** 0.00
- **Dependents:** 0
- **Priority Score:** 1210.0
- **Functions:** 12/12 matched (target 13)
- **Missing functions:** _none_
- **Types:** 0/0 matched (target 1)
- **Missing types:** _none_
- **Tests:** 5/5 matched
- **Provenance warning:** port-lint provenance header matched only after fallback normalization: `src/lib.rs` vs expected `lib.rs`
- **Provenance warning:** port-lint provenance header matched only after fallback normalization: `src/lib.rs` vs expected `lib.rs`
- **Provenance warning:** port-lint provenance header matched only after fallback normalization: `src/lib.rs` vs expected `lib.rs`
- **Proposed provenance header:** `// port-lint: source lib.rs` (current: `// port-lint: source src/lib.rs`)
- **Proposed provenance header:** `// port-lint: source lib.rs` (current: `// port-lint: source src/lib.rs`)
- **Proposed provenance header:** `// port-lint: source lib.rs` (current: `// port-lint: source src/lib.rs`)
- **Lint issues:** 3

### 20. debug

- **Target:** `debug.Debug [PROVENANCE-FALLBACK]`
- **Similarity:** 0.34
- **Dependents:** 0
- **Priority Score:** 506.6
- **Functions:** 3/3 matched (target 11)
- **Missing functions:** _none_
- **Types:** 2/2 matched (target 5)
- **Missing types:** _none_
- **Provenance warning:** port-lint provenance header matched only after fallback normalization: `src/debug.rs` vs expected `debug.rs`
- **Proposed provenance header:** `// port-lint: source debug.rs` (current: `// port-lint: source src/debug.rs`)
- **Lint issues:** 1

### 21. either

- **Target:** `either.Either [PROVENANCE-FALLBACK]`
- **Similarity:** 1.00
- **Dependents:** 0
- **Priority Score:** 100.0
- **Functions:** 0/0 matched
- **Missing functions:** _none_
- **Types:** 1/1 matched (target 3)
- **Missing types:** _none_
- **Provenance warning:** port-lint provenance header matched only after fallback normalization: `src/either.rs` vs expected `either.rs`
- **Proposed provenance header:** `// port-lint: source either.rs` (current: `// port-lint: source src/either.rs`)
- **Lint issues:** 1

### 22. unicode_tables.word_break

- **Target:** `wordbreak.WordBreak [ZERO]`
- **Similarity:** 0.00
- **Dependents:** 0
- **Priority Score:** 10.0
- **Functions:** 0/0 matched (target 5)
- **Missing functions:** _none_
- **Types:** 0/0 matched
- **Missing types:** _none_

### 23. unicode_tables.general_category

- **Target:** `generalcategory.GeneralCategory [ZERO]`
- **Similarity:** 0.00
- **Dependents:** 0
- **Priority Score:** 10.0
- **Functions:** 0/0 matched (target 29)
- **Missing functions:** _none_
- **Types:** 0/0 matched
- **Missing types:** _none_

### 24. unicode_tables.case_folding_simple

- **Target:** `casefoldingsimple.CaseFoldingSimple [ZERO]`
- **Similarity:** 0.00
- **Dependents:** 0
- **Priority Score:** 10.0
- **Functions:** 0/0 matched (target 6)
- **Missing functions:** _none_
- **Types:** 0/0 matched
- **Missing types:** _none_

### 25. unicode_tables.grapheme_cluster_break

- **Target:** `graphemeclusterbreak.GraphemeClusterBreak [ZERO]`
- **Similarity:** 0.00
- **Dependents:** 0
- **Priority Score:** 10.0
- **Functions:** 0/0 matched (target 6)
- **Missing functions:** _none_
- **Types:** 0/0 matched
- **Missing types:** _none_

### 26. unicode_tables.sentence_break

- **Target:** `sentencebreak.SentenceBreak [ZERO]`
- **Similarity:** 0.00
- **Dependents:** 0
- **Priority Score:** 10.0
- **Functions:** 0/0 matched (target 13)
- **Missing functions:** _none_
- **Types:** 0/0 matched
- **Missing types:** _none_

### 27. unicode_tables.property_bool

- **Target:** `propertybool.PropertyBool [ZERO]`
- **Similarity:** 0.00
- **Dependents:** 0
- **Priority Score:** 10.0
- **Functions:** 0/0 matched (target 60)
- **Missing functions:** _none_
- **Types:** 0/0 matched
- **Missing types:** _none_

### 28. unicode_tables.mod

- **Target:** `unicodetables.Mod [STUB] [PROVENANCE-FALLBACK]`
- **Similarity:** 0.00
- **Dependents:** 0
- **Priority Score:** 10.0
- **Functions:** 0/0 matched
- **Missing functions:** _none_
- **Types:** 0/0 matched
- **Missing types:** _none_
- **Provenance warning:** port-lint provenance header matched only after fallback normalization: `src/unicode_tables/mod.rs` vs expected `unicode_tables/mod.rs`
- **Proposed provenance header:** `// port-lint: source unicode_tables/mod.rs` (current: `// port-lint: source src/unicode_tables/mod.rs`)
- **Lint issues:** 1

### 29. unicode_tables.script_extension

- **Target:** `scriptextension.ScriptExtension`
- **Similarity:** 1.00
- **Dependents:** 0
- **Priority Score:** 0.0
- **Functions:** 0/0 matched
- **Missing functions:** _none_
- **Types:** 0/0 matched
- **Missing types:** _none_

### 30. unicode_tables.perl_decimal

- **Target:** `perldecimal.PerlDecimal`
- **Similarity:** 1.00
- **Dependents:** 0
- **Priority Score:** 0.0
- **Functions:** 0/0 matched
- **Missing functions:** _none_
- **Types:** 0/0 matched
- **Missing types:** _none_

### 31. unicode_tables.perl_space

- **Target:** `perlspace.PerlSpace`
- **Similarity:** 1.00
- **Dependents:** 0
- **Priority Score:** 0.0
- **Functions:** 0/0 matched
- **Missing functions:** _none_
- **Types:** 0/0 matched
- **Missing types:** _none_

### 32. unicode_tables.script

- **Target:** `script.Script`
- **Similarity:** 1.00
- **Dependents:** 0
- **Priority Score:** 0.0
- **Functions:** 0/0 matched
- **Missing functions:** _none_
- **Types:** 0/0 matched
- **Missing types:** _none_

### 33. rank

- **Target:** `rank.Rank`
- **Similarity:** 1.00
- **Dependents:** 0
- **Priority Score:** 0.0
- **Functions:** 0/0 matched
- **Missing functions:** _none_
- **Types:** 0/0 matched
- **Missing types:** _none_

## Success Criteria

For each file to be considered "complete":
- **Similarity ≥ 0.85** (Excellent threshold)
- All public APIs ported
- All tests ported
- Documentation ported
- port-lint header present

## Next Commands

```bash
# Initialize task queue for systematic porting
cd tools/ast_distance
./ast_distance --init-tasks ../../tmp/regex-syntax/src rust ../../src kotlin tasks.json ../../AGENTS.md

# Get next high-priority task
./ast_distance --assign tasks.json <agent-id>
```
