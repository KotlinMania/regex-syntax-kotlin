# Immediate Actions - High-Value Files

Based on AST analysis, here are the concrete next steps.

## Summary

- **Files Present:** 31/34 (91.2%)
- **Function parity:** 442/765 matched (target 669) — 57.8%
- **Class/type parity:** 108/134 matched (target 260) — 80.6%
- **Combined symbol parity:** 550/899 matched (target 929) — 61.2%
- **Average inline-code cosine:** 0.81 (function body across 29 matched files)
- **Average documentation cosine:** 0.45 (doc text across 29 matched files)
- **Cheat-zeroed Files:** 2
- **Critical Issues:** 8 files with <0.60 function similarity

## Priority 1: Fix Incomplete High-Dependency Files

No incomplete high-dependency files detected.

## Priority 2: Port Missing High-Value Files

Critical missing files (>10 dependencies):

No missing high-value files detected.

## Detailed Work Items

Every matched file is listed below with function and type symbol parity.

### 1. parser

- **Target:** `parser.Parser`
- **Similarity:** 0.92
- **Dependents:** 2
- **Priority Score:** 2001600.8
- **Functions:** 14/14 matched (target 16)
- **Missing functions:** _none_
- **Types:** 2/2 matched
- **Missing types:** _none_

### 2. unicode

- **Target:** `unicode.Unicode [PROVENANCE-FALLBACK]`
- **Similarity:** 0.27
- **Dependents:** 1
- **Priority Score:** 1094707.2
- **Functions:** 33/39 matched (target 40)
- **Missing functions:** `fmt`, `class`, `imp`, `simple_fold_ok`, `contains_case_map`, `simple_fold_disabled`
- **Types:** 5/8 matched (target 16)
- **Missing types:** `Range`, `Error`, `PropertyValues`
- **Tests:** 6/9 matched
- **Provenance warning:** port-lint provenance header matched only after fallback normalization: `unicode.rs` vs expected `unicode.rs`
- **Provenance warning:** port-lint provenance header matched only after fallback normalization: `unicode.rs` vs expected `unicode.rs`
- **Proposed provenance header:** `// port-lint: source unicode.rs` (current: `// port-lint: source unicode.rs`)
- **Proposed provenance header:** `// port-lint: source unicode.rs` (current: `// port-lint: source unicode.rs`)
- **Lint issues:** 2

### 3. unicode_tables.property_values

- **Target:** `propertyvalues.PropertyValues [PROVENANCE-FALLBACK]`
- **Similarity:** 1.00
- **Dependents:** 1
- **Priority Score:** 1000000.0
- **Functions:** 0/0 matched
- **Missing functions:** _none_
- **Types:** 0/0 matched
- **Missing types:** _none_
- **Provenance warning:** port-lint provenance header matched only after fallback normalization: `unicode_tables/property_values.rs` vs expected `unicode_tables/property_values.rs`
- **Proposed provenance header:** `// port-lint: source unicode_tables/property_values.rs` (current: `// port-lint: source unicode_tables/property_values.rs`)
- **Lint issues:** 1

### 4. unicode_tables.property_names

- **Target:** `propertynames.PropertyNames [PROVENANCE-FALLBACK]`
- **Similarity:** 1.00
- **Dependents:** 1
- **Priority Score:** 1000000.0
- **Functions:** 0/0 matched
- **Missing functions:** _none_
- **Types:** 0/0 matched
- **Missing types:** _none_
- **Provenance warning:** port-lint provenance header matched only after fallback normalization: `unicode_tables/property_names.rs` vs expected `unicode_tables/property_names.rs`
- **Proposed provenance header:** `// port-lint: source unicode_tables/property_names.rs` (current: `// port-lint: source unicode_tables/property_names.rs`)
- **Lint issues:** 1

### 5. unicode_tables.perl_word

- **Target:** `perlword.PerlWord [PROVENANCE-FALLBACK]`
- **Similarity:** 1.00
- **Dependents:** 1
- **Priority Score:** 1000000.0
- **Functions:** 0/0 matched
- **Missing functions:** _none_
- **Types:** 0/0 matched
- **Missing types:** _none_
- **Provenance warning:** port-lint provenance header matched only after fallback normalization: `unicode_tables/perl_word.rs` vs expected `unicode_tables/perl_word.rs`
- **Proposed provenance header:** `// port-lint: source unicode_tables/perl_word.rs` (current: `// port-lint: source unicode_tables/perl_word.rs`)
- **Lint issues:** 1

### 6. unicode_tables.age

- **Target:** `age.Age [PROVENANCE-FALLBACK]`
- **Similarity:** 1.00
- **Dependents:** 1
- **Priority Score:** 1000000.0
- **Functions:** 0/0 matched
- **Missing functions:** _none_
- **Types:** 0/0 matched
- **Missing types:** _none_
- **Provenance warning:** port-lint provenance header matched only after fallback normalization: `unicode_tables/age.rs` vs expected `unicode_tables/age.rs`
- **Proposed provenance header:** `// port-lint: source unicode_tables/age.rs` (current: `// port-lint: source unicode_tables/age.rs`)
- **Lint issues:** 1

### 7. hir.translate

- **Target:** `translate.Translate`
- **Similarity:** 0.31
- **Dependents:** 0
- **Priority Score:** 904906.9
- **Functions:** 55/140 matched (target 65)
- **Missing functions:** `default`, `new`, `eq`, `parse`, `t`, `t_err`, `t_bytes`, `props`, `props_bytes`, `hir_lit`, `hir_blit`, `hir_capture_name`, `hir_quest`, `hir_star`, `hir_plus`, `hir_range`, `hir_alt`, `hir_cat`, `hir_uclass_query`, `hir_uclass_perl_word`, `hir_ascii_uclass`, `hir_ascii_bclass`, `hir_uclass`, `hir_bclass`, `hir_case_fold`, `hir_negate`, `uclass`, `bclass`, `class_case_fold`, `class_negate`, `hir_union`, `hir_difference`, `hir_look`, `empty`, `literal`, `literal_case_insensitive`, `dot`, `assertions`, `group`, `line_anchors`, `escape`, `repetition`, `cat_alt`, `cat_class_flattened`, `class_ascii`, `class_ascii_multiple`, `class_perl_unicode`, `class_perl_ascii`, `class_perl_word_disabled`, `class_perl_space_disabled`, `class_perl_digit_disabled`, `class_unicode_gencat`, `class_unicode_gencat_disabled`, `class_unicode_script`, `class_unicode_script_disabled`, `class_unicode_age`, `class_unicode_any_empty`, `class_unicode_age_disabled`, `class_bracketed`, `class_bracketed_union`, `class_bracketed_nested`, `class_bracketed_intersect`, `class_bracketed_intersect_negate`, `class_bracketed_difference`, `class_bracketed_symmetric_difference`, `ignore_whitespace`, `analysis_is_utf8`, `analysis_captures_len`, `analysis_static_captures_len`, `analysis_is_all_assertions`, `analysis_look_set_prefix_any`, `analysis_is_anchored`, `analysis_is_any_anchored`, `analysis_can_empty`, `analysis_is_literal`, `analysis_is_alternation_literal`, `smart_repetition`, `smart_concat`, `smart_alternation`, `regression_alt_empty_concat`, `regression_empty_alt`, `regression_singleton_alt`, `regression_fuzz_match`, `regression_fuzz_difference1`, `regression_fuzz_char_decrement1`
- **Types:** 5/9 matched (target 16)
- **Missing types:** `Result`, `Output`, `Err`, `TestError`
- **Tests:** 0/83 matched
- **Lint issues:** 2

### 8. ast.parse

- **Target:** `parse.Parse`
- **Similarity:** 0.41
- **Dependents:** 0
- **Priority Score:** 513305.8
- **Functions:** 76/122 matched (target 81)
- **Missing functions:** `is_hex`, `default`, `eq`, `s`, `parser_octal`, `parser_empty_min_range`, `parser_nest_limit`, `parser_ignore_whitespace`, `nspan`, `npos`, `span_range`, `lit`, `meta_lit`, `lit_with`, `concat`, `concat_with`, `alt`, `group`, `flag_set`, `parse_nest_limit`, `parse_comments`, `parse_holistic`, `parse_ignore_whitespace`, `parse_newlines`, `parse_alternate`, `parse_unsupported_lookaround`, `parse_primitive_non_escape`, `parse_unsupported_backreference`, `parse_hex_two`, `parse_hex_four`, `parse_hex_eight`, `union`, `intersection`, `difference`, `symdifference`, `itemset`, `item_ascii`, `item_unicode`, `item_perl`, `item_bracket`, `empty`, `range`, `alnum`, `lower`, `regression_454_nest_too_big`, `regression_455_trailing_dash_ignore_whitespace`
- **Types:** 7/11 matched (target 18)
- **Missing types:** `Result`, `Output`, `Err`, `TestError`
- **Tests:** 0/44 matched
- **Lint issues:** 2

### 9. hir.mod

- **Target:** `hir.Hir [STUB]`
- **Similarity:** 0.00
- **Dependents:** 0
- **Priority Score:** 474510.0
- **Functions:** 79/123 matched (target 187)
- **Missing functions:** `class`, `drop`, `set_insert`, `set_remove`, `set_subtract`, `set_union`, `set_intersect`, `uclass`, `bclass`, `uranges`, `ucasefold`, `uunion`, `uintersect`, `udifference`, `usymdifference`, `unegate`, `branges`, `bcasefold`, `bunion`, `bintersect`, `bdifference`, `bsymdifference`, `bnegate`, `class_range_canonical_unicode`, `class_range_canonical_bytes`, `class_canonicalize_unicode`, `class_canonicalize_bytes`, `class_case_fold_unicode`, `class_case_fold_unicode_disabled`, `class_case_fold_unicode_disabled_panics`, `class_case_fold_bytes`, `class_negate_unicode`, `class_negate_bytes`, `class_union_unicode`, `class_union_bytes`, `class_intersect_unicode`, `class_intersect_bytes`, `class_difference_unicode`, `class_difference_bytes`, `class_symmetric_difference_unicode`, `class_symmetric_difference_bytes`, `no_stack_overflow_on_drop`, `look_set_iter`, `look_set_debug`
- **Types:** 20/22 matched (target 43)
- **Missing types:** `Item`, `Bound`
- **Tests:** 0/37 matched

### 10. utf8

- **Target:** `utf8.Utf8 [PROVENANCE-FALLBACK]`
- **Similarity:** 0.61
- **Dependents:** 0
- **Priority Score:** 72903.9
- **Functions:** 18/23 matched (target 39)
- **Missing functions:** `into_iter`, `fmt`, `rutf8`, `never_accepts_surrogate_codepoints`, `encode_surrogate`
- **Types:** 4/6 matched (target 9)
- **Missing types:** `IntoIter`, `Item`
- **Tests:** 3/6 matched
- **Provenance warning:** port-lint provenance header matched only after fallback normalization: `utf8.rs` vs expected `utf8.rs`
- **Provenance warning:** port-lint provenance header matched only after fallback normalization: `utf8.rs` vs expected `utf8.rs`
- **Proposed provenance header:** `// port-lint: source utf8.rs` (current: `// port-lint: source utf8.rs`)
- **Proposed provenance header:** `// port-lint: source utf8.rs` (current: `// port-lint: source utf8.rs`)
- **Lint issues:** 2

### 11. hir.print

- **Target:** `print.Print`
- **Similarity:** 0.76
- **Dependents:** 0
- **Priority Score:** 62902.4
- **Functions:** 20/24 matched (target 29)
- **Missing functions:** `default`, `roundtrip`, `roundtrip_bytes`, `roundtrip_with`
- **Types:** 3/5 matched (target 4)
- **Missing types:** `Output`, `Err`
- **Tests:** 10/13 matched

### 12. ast.print

- **Target:** `commonMain.kotlin.io.github.kotlinmania.regexsyntax.ast.print.Print`
- **Similarity:** 0.77
- **Dependents:** 0
- **Priority Score:** 54102.3
- **Functions:** 33/36 matched (target 39)
- **Missing functions:** `default`, `roundtrip`, `roundtrip_with`
- **Types:** 3/5 matched (target 4)
- **Missing types:** `Output`, `Err`
- **Tests:** 9/11 matched

### 13. lib

- **Target:** `regexsyntax.MetaCharacter [PROVENANCE-FALLBACK]`
- **Similarity:** 0.37
- **Dependents:** 0
- **Priority Score:** 51206.3
- **Functions:** 7/12 matched (target 7)
- **Missing functions:** `escape_meta`, `word_byte`, `word_char`, `word_char_disabled_panic`, `word_char_disabled_error`
- **Types:** 0/0 matched
- **Missing types:** _none_
- **Tests:** 0/5 matched
- **Provenance warning:** port-lint provenance header matched only after fallback normalization: `lib.rs` vs expected `lib.rs`
- **Proposed provenance header:** `// port-lint: source lib.rs` (current: `// port-lint: source lib.rs`)
- **Lint issues:** 1

### 14. ast.mod

- **Target:** `ast.Ast [STUB]`
- **Similarity:** 0.00
- **Dependents:** 0
- **Priority Score:** 48710.0
- **Functions:** 42/46 matched (target 65)
- **Missing functions:** `arbitrary`, `size_hint`, `drop`, `ast_size`
- **Types:** 41/41 matched (target 105)
- **Missing types:** _none_
- **Tests:** 1/2 matched

### 15. hir.interval

- **Target:** `interval.Interval`
- **Similarity:** 0.59
- **Dependents:** 0
- **Priority Score:** 42804.1
- **Functions:** 20/23 matched (target 34)
- **Missing functions:** `eq`, `create`, `as_u32`
- **Types:** 4/5 matched (target 7)
- **Missing types:** `Item`

### 16. error

- **Target:** `regexsyntax.Error`
- **Similarity:** 0.63
- **Dependents:** 0
- **Priority Score:** 21503.7
- **Functions:** 10/12 matched (target 19)
- **Missing functions:** `fmt`, `assert_panic_message`
- **Types:** 3/3 matched (target 6)
- **Missing types:** _none_
- **Tests:** 2/3 matched

### 17. ast.visitor

- **Target:** `commonMain.kotlin.io.github.kotlinmania.regexsyntax.ast.visitor.Visitor`
- **Similarity:** 0.74
- **Dependents:** 0
- **Priority Score:** 12802.6
- **Functions:** 22/23 matched (target 26)
- **Missing functions:** `fmt`
- **Types:** 5/5 matched (target 15)
- **Missing types:** _none_

### 18. hir.visitor

- **Target:** `visitor.Visitor`
- **Similarity:** 0.73
- **Dependents:** 0
- **Priority Score:** 1302.7
- **Functions:** 10/10 matched (target 11)
- **Missing functions:** _none_
- **Types:** 3/3 matched (target 7)
- **Missing types:** _none_

### 19. debug

- **Target:** `debug.Debug`
- **Similarity:** 0.35
- **Dependents:** 0
- **Priority Score:** 506.5
- **Functions:** 3/3 matched (target 11)
- **Missing functions:** _none_
- **Types:** 2/2 matched (target 5)
- **Missing types:** _none_

### 20. either

- **Target:** `either.Either`
- **Similarity:** 1.00
- **Dependents:** 0
- **Priority Score:** 100.0
- **Functions:** 0/0 matched
- **Missing functions:** _none_
- **Types:** 1/1 matched (target 3)
- **Missing types:** _none_

### 21. rank

- **Target:** `rank.Rank [PROVENANCE-FALLBACK]`
- **Similarity:** 1.00
- **Dependents:** 0
- **Priority Score:** 0.0
- **Functions:** 0/0 matched
- **Missing functions:** _none_
- **Types:** 0/0 matched
- **Missing types:** _none_
- **Provenance warning:** port-lint provenance header matched only after fallback normalization: `rank.rs` vs expected `rank.rs`
- **Proposed provenance header:** `// port-lint: source rank.rs` (current: `// port-lint: source rank.rs`)
- **Lint issues:** 1

### 22. unicode_tables.script

- **Target:** `script.Script [PROVENANCE-FALLBACK]`
- **Similarity:** 1.00
- **Dependents:** 0
- **Priority Score:** 0.0
- **Functions:** 0/0 matched
- **Missing functions:** _none_
- **Types:** 0/0 matched
- **Missing types:** _none_
- **Provenance warning:** port-lint provenance header matched only after fallback normalization: `unicode_tables/script.rs` vs expected `unicode_tables/script.rs`
- **Proposed provenance header:** `// port-lint: source unicode_tables/script.rs` (current: `// port-lint: source unicode_tables/script.rs`)
- **Lint issues:** 1

### 23. unicode_tables.perl_space

- **Target:** `perlspace.PerlSpace [PROVENANCE-FALLBACK]`
- **Similarity:** 1.00
- **Dependents:** 0
- **Priority Score:** 0.0
- **Functions:** 0/0 matched
- **Missing functions:** _none_
- **Types:** 0/0 matched
- **Missing types:** _none_
- **Provenance warning:** port-lint provenance header matched only after fallback normalization: `unicode_tables/perl_space.rs` vs expected `unicode_tables/perl_space.rs`
- **Proposed provenance header:** `// port-lint: source unicode_tables/perl_space.rs` (current: `// port-lint: source unicode_tables/perl_space.rs`)
- **Lint issues:** 1

### 24. unicode_tables.word_break

- **Target:** `wordbreak.WordBreak [PROVENANCE-FALLBACK]`
- **Similarity:** 1.00
- **Dependents:** 0
- **Priority Score:** 0.0
- **Functions:** 0/0 matched
- **Missing functions:** _none_
- **Types:** 0/0 matched
- **Missing types:** _none_
- **Provenance warning:** port-lint provenance header matched only after fallback normalization: `unicode_tables/word_break.rs` vs expected `unicode_tables/word_break.rs`
- **Proposed provenance header:** `// port-lint: source unicode_tables/word_break.rs` (current: `// port-lint: source unicode_tables/word_break.rs`)
- **Lint issues:** 1

### 25. unicode_tables.perl_decimal

- **Target:** `perldecimal.PerlDecimal [PROVENANCE-FALLBACK]`
- **Similarity:** 1.00
- **Dependents:** 0
- **Priority Score:** 0.0
- **Functions:** 0/0 matched
- **Missing functions:** _none_
- **Types:** 0/0 matched
- **Missing types:** _none_
- **Provenance warning:** port-lint provenance header matched only after fallback normalization: `unicode_tables/perl_decimal.rs` vs expected `unicode_tables/perl_decimal.rs`
- **Proposed provenance header:** `// port-lint: source unicode_tables/perl_decimal.rs` (current: `// port-lint: source unicode_tables/perl_decimal.rs`)
- **Lint issues:** 1

### 26. unicode_tables.property_bool

- **Target:** `propertybool.PropertyBool [PROVENANCE-FALLBACK]`
- **Similarity:** 1.00
- **Dependents:** 0
- **Priority Score:** 0.0
- **Functions:** 0/0 matched
- **Missing functions:** _none_
- **Types:** 0/0 matched
- **Missing types:** _none_
- **Provenance warning:** port-lint provenance header matched only after fallback normalization: `unicode_tables/property_bool.rs` vs expected `unicode_tables/property_bool.rs`
- **Proposed provenance header:** `// port-lint: source unicode_tables/property_bool.rs` (current: `// port-lint: source unicode_tables/property_bool.rs`)
- **Lint issues:** 1

### 27. unicode_tables.sentence_break

- **Target:** `sentencebreak.SentenceBreak [PROVENANCE-FALLBACK]`
- **Similarity:** 1.00
- **Dependents:** 0
- **Priority Score:** 0.0
- **Functions:** 0/0 matched
- **Missing functions:** _none_
- **Types:** 0/0 matched
- **Missing types:** _none_
- **Provenance warning:** port-lint provenance header matched only after fallback normalization: `unicode_tables/sentence_break.rs` vs expected `unicode_tables/sentence_break.rs`
- **Proposed provenance header:** `// port-lint: source unicode_tables/sentence_break.rs` (current: `// port-lint: source unicode_tables/sentence_break.rs`)
- **Lint issues:** 1

### 28. unicode_tables.general_category

- **Target:** `generalcategory.GeneralCategory [PROVENANCE-FALLBACK]`
- **Similarity:** 1.00
- **Dependents:** 0
- **Priority Score:** 0.0
- **Functions:** 0/0 matched
- **Missing functions:** _none_
- **Types:** 0/0 matched
- **Missing types:** _none_
- **Provenance warning:** port-lint provenance header matched only after fallback normalization: `unicode_tables/general_category.rs` vs expected `unicode_tables/general_category.rs`
- **Proposed provenance header:** `// port-lint: source unicode_tables/general_category.rs` (current: `// port-lint: source unicode_tables/general_category.rs`)
- **Lint issues:** 1

### 29. unicode_tables.script_extension

- **Target:** `scriptextension.ScriptExtension [PROVENANCE-FALLBACK]`
- **Similarity:** 1.00
- **Dependents:** 0
- **Priority Score:** 0.0
- **Functions:** 0/0 matched
- **Missing functions:** _none_
- **Types:** 0/0 matched
- **Missing types:** _none_
- **Provenance warning:** port-lint provenance header matched only after fallback normalization: `unicode_tables/script_extension.rs` vs expected `unicode_tables/script_extension.rs`
- **Proposed provenance header:** `// port-lint: source unicode_tables/script_extension.rs` (current: `// port-lint: source unicode_tables/script_extension.rs`)
- **Lint issues:** 1

### 30. unicode_tables.case_folding_simple

- **Target:** `casefoldingsimple.CaseFoldingSimple [PROVENANCE-FALLBACK]`
- **Similarity:** 1.00
- **Dependents:** 0
- **Priority Score:** 0.0
- **Functions:** 0/0 matched
- **Missing functions:** _none_
- **Types:** 0/0 matched
- **Missing types:** _none_
- **Provenance warning:** port-lint provenance header matched only after fallback normalization: `unicode_tables/case_folding_simple.rs` vs expected `unicode_tables/case_folding_simple.rs`
- **Proposed provenance header:** `// port-lint: source unicode_tables/case_folding_simple.rs` (current: `// port-lint: source unicode_tables/case_folding_simple.rs`)
- **Lint issues:** 1

### 31. unicode_tables.grapheme_cluster_break

- **Target:** `graphemeclusterbreak.GraphemeClusterBreak [PROVENANCE-FALLBACK]`
- **Similarity:** 1.00
- **Dependents:** 0
- **Priority Score:** 0.0
- **Functions:** 0/0 matched
- **Missing functions:** _none_
- **Types:** 0/0 matched
- **Missing types:** _none_
- **Provenance warning:** port-lint provenance header matched only after fallback normalization: `unicode_tables/grapheme_cluster_break.rs` vs expected `unicode_tables/grapheme_cluster_break.rs`
- **Proposed provenance header:** `// port-lint: source unicode_tables/grapheme_cluster_break.rs` (current: `// port-lint: source unicode_tables/grapheme_cluster_break.rs`)
- **Lint issues:** 1

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
./ast_distance --init-tasks ../../tmp/regex-syntax rust ../../src/commonMain/kotlin/io/github/kotlinmania/regexsyntax kotlin tasks.json ../../AGENTS.md

# Get next high-priority task
./ast_distance --assign tasks.json <agent-id>
```
## Reexport / Wiring Modules

These files match `reexport_modules` patterns in `.ast_distance_config.json`. They are filtered out of
normal priority and missing-file ladders because they are wiring
modules, not direct logic ports. Consult them for call-site routing;
do not treat them as the next implementation target by default.

### Missing

| Source | Expected target | Deps | Source path | Expected path |
|--------|-----------------|------|-------------|---------------|
| `unicode_tables.mod` | `unicodetables.Mod` | 0 | `src/unicode_tables/mod.rs` | `unicodetables/Mod.kt` |

