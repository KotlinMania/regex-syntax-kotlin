# Immediate Actions - High-Value Files

Based on AST analysis, here are the concrete next steps.

## Summary

- **Files Present:** 31/33 (93.9%)
- **Function parity:** 380/758 matched (target 572) — 50.1%
- **Class/type parity:** 104/134 matched (target 249) — 77.6%
- **Combined symbol parity:** 484/892 matched (target 821) — 54.3%
- **Average inline-code cosine:** 0.71 (function body across 27 matched files)
- **Average documentation cosine:** 0.42 (doc text across 27 matched files)
- **Cheat-zeroed Files:** 7
- **Critical Issues:** 13 files with <0.60 function similarity

## Priority 1: Fix Incomplete High-Dependency Files

No incomplete high-dependency files detected.

## Priority 2: Port Missing High-Value Files

Critical missing files (>10 dependencies):

No missing high-value files detected.

## Detailed Work Items

Every matched file is listed below with function and type symbol parity.

### 1. unicode

- **Target:** `unicode.Unicode`
- **Similarity:** 0.21
- **Dependents:** 1
- **Priority Score:** 1154707.9
- **Functions:** 27/39 matched (target 32)
- **Missing functions:** `fmt`, `class`, `imp`, `simple_fold_ok`, `contains_case_map`, `simple_fold_k`, `simple_fold_a`, `simple_fold_disabled`, `range_contains`, `regression_466`, `sym_normalize`, `valid_utf8_symbolic`
- **Types:** 5/8 matched (target 15)
- **Missing types:** `Range`, `Error`, `PropertyValues`
- **Tests:** 0/9 matched

### 2. parser

- **Target:** `parser.Parser [PROVENANCE-FALLBACK]`
- **Similarity:** 0.92
- **Dependents:** 1
- **Priority Score:** 1001600.8
- **Functions:** 14/14 matched (target 16)
- **Missing functions:** _none_
- **Types:** 2/2 matched
- **Missing types:** _none_
- **Provenance warning:** port-lint provenance header matched only after fallback normalization: `src/parser.rs` vs expected `parser.rs`
- **Proposed provenance header:** `// port-lint: source parser.rs` (current: `// port-lint: source src/parser.rs`)
- **Lint issues:** 1

### 3. unicode_tables.property_names

- **Target:** `propertynames.PropertyNames`
- **Similarity:** 1.00
- **Dependents:** 1
- **Priority Score:** 1000000.0
- **Functions:** 0/0 matched
- **Missing functions:** _none_
- **Types:** 0/0 matched
- **Missing types:** _none_

### 4. unicode_tables.age

- **Target:** `age.Age`
- **Similarity:** 1.00
- **Dependents:** 1
- **Priority Score:** 1000000.0
- **Functions:** 0/0 matched
- **Missing functions:** _none_
- **Types:** 0/0 matched
- **Missing types:** _none_

### 5. unicode_tables.perl_word

- **Target:** `perlword.PerlWord`
- **Similarity:** 1.00
- **Dependents:** 1
- **Priority Score:** 1000000.0
- **Functions:** 0/0 matched
- **Missing functions:** _none_
- **Types:** 0/0 matched
- **Missing types:** _none_

### 6. unicode_tables.property_values

- **Target:** `propertyvalues.PropertyValues`
- **Similarity:** 1.00
- **Dependents:** 1
- **Priority Score:** 1000000.0
- **Functions:** 0/0 matched
- **Missing functions:** _none_
- **Types:** 0/0 matched
- **Missing types:** _none_

### 7. hir.translate

- **Target:** `translate.Translate [PROVENANCE-FALLBACK]`
- **Similarity:** 0.31
- **Dependents:** 0
- **Priority Score:** 904906.9
- **Functions:** 55/140 matched (target 65)
- **Missing functions:** `default`, `new`, `eq`, `parse`, `t`, `t_err`, `t_bytes`, `props`, `props_bytes`, `hir_lit`, `hir_blit`, `hir_capture_name`, `hir_quest`, `hir_star`, `hir_plus`, `hir_range`, `hir_alt`, `hir_cat`, `hir_uclass_query`, `hir_uclass_perl_word`, `hir_ascii_uclass`, `hir_ascii_bclass`, `hir_uclass`, `hir_bclass`, `hir_case_fold`, `hir_negate`, `uclass`, `bclass`, `class_case_fold`, `class_negate`, `hir_union`, `hir_difference`, `hir_look`, `empty`, `literal`, `literal_case_insensitive`, `dot`, `assertions`, `group`, `line_anchors`, `escape`, `repetition`, `cat_alt`, `cat_class_flattened`, `class_ascii`, `class_ascii_multiple`, `class_perl_unicode`, `class_perl_ascii`, `class_perl_word_disabled`, `class_perl_space_disabled`, `class_perl_digit_disabled`, `class_unicode_gencat`, `class_unicode_gencat_disabled`, `class_unicode_script`, `class_unicode_script_disabled`, `class_unicode_age`, `class_unicode_any_empty`, `class_unicode_age_disabled`, `class_bracketed`, `class_bracketed_union`, `class_bracketed_nested`, `class_bracketed_intersect`, `class_bracketed_intersect_negate`, `class_bracketed_difference`, `class_bracketed_symmetric_difference`, `ignore_whitespace`, `analysis_is_utf8`, `analysis_captures_len`, `analysis_static_captures_len`, `analysis_is_all_assertions`, `analysis_look_set_prefix_any`, `analysis_is_anchored`, `analysis_is_any_anchored`, `analysis_can_empty`, `analysis_is_literal`, `analysis_is_alternation_literal`, `smart_repetition`, `smart_concat`, `smart_alternation`, `regression_alt_empty_concat`, `regression_empty_alt`, `regression_singleton_alt`, `regression_fuzz_match`, `regression_fuzz_difference1`, `regression_fuzz_char_decrement1`
- **Types:** 5/9 matched (target 16)
- **Missing types:** `Result`, `Output`, `Err`, `TestError`
- **Tests:** 0/83 matched
- **Provenance warning:** port-lint provenance header matched only after fallback normalization: `src/hir/translate.rs` vs expected `hir/translate.rs`
- **Proposed provenance header:** `// port-lint: source hir/translate.rs` (current: `// port-lint: source src/hir/translate.rs`)
- **Lint issues:** 3

### 8. ast.parse

- **Target:** `parse.Parse [PROVENANCE-FALLBACK]`
- **Similarity:** 0.32
- **Dependents:** 0
- **Priority Score:** 743306.8
- **Functions:** 55/122 matched (target 59)
- **Missing functions:** `is_hex`, `is_capture_char`, `default`, `parse_group`, `parse_capture_name`, `parse_flags`, `parse_flag`, `parse_primitive`, `parse_escape`, `maybe_parse_special_word_boundary`, `parse_octal`, `parse_hex`, `parse_hex_digits`, `parse_hex_brace`, `parse_decimal`, `parse_set_class`, `parse_set_class_range`, `parse_set_class_item`, `parse_set_class_open`, `maybe_parse_ascii_class`, `parse_unicode_class`, `parse_perl_class`, `specialize_err`, `eq`, `s`, `parser_octal`, `parser_empty_min_range`, `parser_nest_limit`, `parser_ignore_whitespace`, `nspan`, `npos`, `span_range`, `lit`, `meta_lit`, `lit_with`, `concat`, `concat_with`, `alt`, `group`, `flag_set`, `parse_nest_limit`, `parse_comments`, `parse_holistic`, `parse_ignore_whitespace`, `parse_newlines`, `parse_alternate`, `parse_unsupported_lookaround`, `parse_primitive_non_escape`, `parse_unsupported_backreference`, `parse_hex_two`, `parse_hex_four`, `parse_hex_eight`, `union`, `intersection`, `difference`, `symdifference`, `itemset`, `item_ascii`, `item_unicode`, `item_perl`, `item_bracket`, `empty`, `range`, `alnum`, `lower`, `regression_454_nest_too_big`, `regression_455_trailing_dash_ignore_whitespace`
- **Types:** 5/11 matched (target 15)
- **Missing types:** `Result`, `ParserI`, `NestLimiter`, `Output`, `Err`, `TestError`
- **Tests:** 0/44 matched
- **Provenance warning:** port-lint provenance header matched only after fallback normalization: `src/ast/parse.rs` vs expected `ast/parse.rs`
- **Proposed provenance header:** `// port-lint: source ast/parse.rs` (current: `// port-lint: source src/ast/parse.rs`)
- **Lint issues:** 3

### 9. hir.mod

- **Target:** `hir.Hir [STUB] [PROVENANCE-FALLBACK]`
- **Similarity:** 0.00
- **Dependents:** 0
- **Priority Score:** 484510.0
- **Functions:** 78/123 matched (target 176)
- **Missing functions:** `fmt`, `class`, `drop`, `set_insert`, `set_remove`, `set_subtract`, `set_union`, `set_intersect`, `uclass`, `bclass`, `uranges`, `ucasefold`, `uunion`, `uintersect`, `udifference`, `usymdifference`, `unegate`, `branges`, `bcasefold`, `bunion`, `bintersect`, `bdifference`, `bsymdifference`, `bnegate`, `class_range_canonical_unicode`, `class_range_canonical_bytes`, `class_canonicalize_unicode`, `class_canonicalize_bytes`, `class_case_fold_unicode`, `class_case_fold_unicode_disabled`, `class_case_fold_unicode_disabled_panics`, `class_case_fold_bytes`, `class_negate_unicode`, `class_negate_bytes`, `class_union_unicode`, `class_union_bytes`, `class_intersect_unicode`, `class_intersect_bytes`, `class_difference_unicode`, `class_difference_bytes`, `class_symmetric_difference_unicode`, `class_symmetric_difference_bytes`, `no_stack_overflow_on_drop`, `look_set_iter`, `look_set_debug`
- **Types:** 20/22 matched (target 43)
- **Missing types:** `Item`, `Bound`
- **Tests:** 0/37 matched
- **Provenance warning:** port-lint provenance header matched only after fallback normalization: `src/hir/mod.rs` vs expected `hir/mod.rs`
- **Proposed provenance header:** `// port-lint: source hir/mod.rs` (current: `// port-lint: source src/hir/mod.rs`)
- **Lint issues:** 1

### 10. hir.print

- **Target:** `print.Print [PROVENANCE-FALLBACK]`
- **Similarity:** 0.21
- **Dependents:** 0
- **Priority Score:** 192907.9
- **Functions:** 8/24 matched (target 14)
- **Missing functions:** `default`, `new`, `build`, `roundtrip`, `roundtrip_bytes`, `roundtrip_with`, `print_literal`, `print_class`, `print_anchor`, `print_word_boundary`, `print_repetition`, `print_group`, `print_alternation`, `regression_repetition_concat`, `regression_repetition_alternation`, `regression_alternation_concat`
- **Types:** 2/5 matched (target 2)
- **Missing types:** `PrinterBuilder`, `Output`, `Err`
- **Tests:** 0/13 matched
- **Provenance warning:** port-lint provenance header matched only after fallback normalization: `src/hir/print.rs` vs expected `hir/print.rs`
- **Proposed provenance header:** `// port-lint: source hir/print.rs` (current: `// port-lint: source src/hir/print.rs`)
- **Lint issues:** 1

### 11. ast.print

- **Target:** `kotlin.io.github.kotlinmania.regexsyntax.ast.print.Print [PROVENANCE-FALLBACK]`
- **Similarity:** 0.48
- **Dependents:** 0
- **Priority Score:** 144105.2
- **Functions:** 24/36 matched (target 28)
- **Missing functions:** `default`, `roundtrip`, `roundtrip_with`, `print_literal`, `print_dot`, `print_concat`, `print_alternation`, `print_assertion`, `print_repetition`, `print_flags`, `print_group`, `print_class`
- **Types:** 3/5 matched (target 3)
- **Missing types:** `Output`, `Err`
- **Tests:** 0/11 matched
- **Provenance warning:** port-lint provenance header matched only after fallback normalization: `src/ast/print.rs` vs expected `ast/print.rs`
- **Proposed provenance header:** `// port-lint: source ast/print.rs` (current: `// port-lint: source src/ast/print.rs`)
- **Lint issues:** 1

### 12. utf8

- **Target:** `utf8.Utf8 [ZERO] [PROVENANCE-FALLBACK]`
- **Similarity:** 0.00
- **Dependents:** 0
- **Priority Score:** 102910.0
- **Functions:** 15/23 matched (target 30)
- **Missing functions:** `into_iter`, `fmt`, `rutf8`, `never_accepts_surrogate_codepoints`, `codepoints_no_surrogates`, `single_codepoint_one_sequence`, `bmp`, `encode_surrogate`
- **Types:** 4/6 matched (target 8)
- **Missing types:** `IntoIter`, `Item`
- **Tests:** 0/6 matched
- **Provenance warning:** port-lint provenance header matched only after fallback normalization: `src/utf8.rs` vs expected `utf8.rs`
- **Proposed provenance header:** `// port-lint: source utf8.rs` (current: `// port-lint: source src/utf8.rs`)
- **Lint issues:** 1

### 13. ast.mod

- **Target:** `ast.Ast [STUB] [PROVENANCE-FALLBACK]`
- **Similarity:** 0.00
- **Dependents:** 0
- **Priority Score:** 88710.0
- **Functions:** 38/46 matched (target 53)
- **Missing functions:** `fmt`, `cmp`, `partial_cmp`, `arbitrary`, `size_hint`, `drop`, `no_stack_overflow_on_drop`, `ast_size`
- **Types:** 41/41 matched (target 104)
- **Missing types:** _none_
- **Tests:** 0/2 matched
- **Provenance warning:** port-lint provenance header matched only after fallback normalization: `src/ast/mod.rs` vs expected `ast/mod.rs`
- **Proposed provenance header:** `// port-lint: source ast/mod.rs` (current: `// port-lint: source src/ast/mod.rs`)
- **Lint issues:** 1

### 14. error

- **Target:** `regexsyntax.Error [PROVENANCE-FALLBACK]`
- **Similarity:** 0.38
- **Dependents:** 0
- **Priority Score:** 51506.2
- **Functions:** 7/12 matched (target 14)
- **Missing functions:** `from`, `fmt`, `assert_panic_message`, `regression_464`, `repetition_quantifier_expects_a_valid_decimal`
- **Types:** 3/3 matched (target 5)
- **Missing types:** _none_
- **Tests:** 0/3 matched
- **Provenance warning:** port-lint provenance header matched only after fallback normalization: `src/error.rs` vs expected `error.rs`
- **Proposed provenance header:** `// port-lint: source error.rs` (current: `// port-lint: source src/error.rs`)
- **Lint issues:** 1

### 15. lib

- **Target:** `regexsyntax.WordCharacter [STUB] [PROVENANCE-FALLBACK]`
- **Similarity:** 0.00
- **Dependents:** 0
- **Priority Score:** 51210.0
- **Functions:** 7/12 matched (target 7)
- **Missing functions:** `escape_meta`, `word_byte`, `word_char`, `word_char_disabled_panic`, `word_char_disabled_error`
- **Types:** 0/0 matched
- **Missing types:** _none_
- **Tests:** 0/5 matched
- **Provenance warning:** port-lint provenance header matched only after fallback normalization: `src/lib.rs` vs expected `lib.rs`
- **Provenance warning:** port-lint provenance header matched only after fallback normalization: `src/lib.rs` vs expected `lib.rs`
- **Proposed provenance header:** `// port-lint: source lib.rs` (current: `// port-lint: source src/lib.rs`)
- **Proposed provenance header:** `// port-lint: source lib.rs` (current: `// port-lint: source src/lib.rs`)
- **Lint issues:** 2

### 16. hir.interval

- **Target:** `interval.Interval [STUB] [PROVENANCE-FALLBACK]`
- **Similarity:** 0.00
- **Dependents:** 0
- **Priority Score:** 42810.0
- **Functions:** 20/23 matched (target 34)
- **Missing functions:** `eq`, `create`, `as_u32`
- **Types:** 4/5 matched (target 7)
- **Missing types:** `Item`
- **Provenance warning:** port-lint provenance header matched only after fallback normalization: `src/hir/interval.rs` vs expected `hir/interval.rs`
- **Proposed provenance header:** `// port-lint: source hir/interval.rs` (current: `// port-lint: source src/hir/interval.rs`)
- **TODOs:** 2
- **Lint issues:** 1

### 17. ast.visitor

- **Target:** `kotlin.io.github.kotlinmania.regexsyntax.ast.visitor.Visitor [PROVENANCE-FALLBACK]`
- **Similarity:** 0.72
- **Dependents:** 0
- **Priority Score:** 22802.8
- **Functions:** 21/23 matched (target 25)
- **Missing functions:** `new`, `fmt`
- **Types:** 5/5 matched (target 15)
- **Missing types:** _none_
- **Provenance warning:** port-lint provenance header matched only after fallback normalization: `src/ast/visitor.rs` vs expected `ast/visitor.rs`
- **Proposed provenance header:** `// port-lint: source ast/visitor.rs` (current: `// port-lint: source src/ast/visitor.rs`)
- **Lint issues:** 1

### 18. debug

- **Target:** `debug.Debug [ZERO] [PROVENANCE-FALLBACK]`
- **Similarity:** 0.00
- **Dependents:** 0
- **Priority Score:** 20510.0
- **Functions:** 2/3 matched (target 9)
- **Missing functions:** `fmt`
- **Types:** 1/2 matched (target 4)
- **Missing types:** `Byte`
- **Provenance warning:** port-lint provenance header matched only after fallback normalization: `src/debug.rs` vs expected `debug.rs`
- **Proposed provenance header:** `// port-lint: source debug.rs` (current: `// port-lint: source src/debug.rs`)
- **Lint issues:** 1

### 19. hir.visitor

- **Target:** `visitor.Visitor [PROVENANCE-FALLBACK]`
- **Similarity:** 0.67
- **Dependents:** 0
- **Priority Score:** 11303.3
- **Functions:** 9/10 matched
- **Missing functions:** `new`
- **Types:** 3/3 matched (target 7)
- **Missing types:** _none_
- **Provenance warning:** port-lint provenance header matched only after fallback normalization: `src/hir/visitor.rs` vs expected `hir/visitor.rs`
- **Proposed provenance header:** `// port-lint: source hir/visitor.rs` (current: `// port-lint: source src/hir/visitor.rs`)
- **Lint issues:** 1

### 20. either

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

### 21. rank

- **Target:** `rank.Rank [ZERO] [PROVENANCE-FALLBACK]`
- **Similarity:** 0.00
- **Dependents:** 0
- **Priority Score:** 10.0
- **Functions:** 0/0 matched
- **Missing functions:** _none_
- **Types:** 0/0 matched
- **Missing types:** _none_
- **Provenance warning:** port-lint provenance header matched only after fallback normalization: `src/rank.rs` vs expected `rank.rs`
- **Proposed provenance header:** `// port-lint: source rank.rs` (current: `// port-lint: source src/rank.rs`)
- **Lint issues:** 1

### 22. unicode_tables.script

- **Target:** `script.Script`
- **Similarity:** 1.00
- **Dependents:** 0
- **Priority Score:** 0.0
- **Functions:** 0/0 matched
- **Missing functions:** _none_
- **Types:** 0/0 matched
- **Missing types:** _none_

### 23. unicode_tables.word_break

- **Target:** `wordbreak.WordBreak`
- **Similarity:** 1.00
- **Dependents:** 0
- **Priority Score:** 0.0
- **Functions:** 0/0 matched
- **Missing functions:** _none_
- **Types:** 0/0 matched
- **Missing types:** _none_

### 24. unicode_tables.grapheme_cluster_break

- **Target:** `graphemeclusterbreak.GraphemeClusterBreak`
- **Similarity:** 1.00
- **Dependents:** 0
- **Priority Score:** 0.0
- **Functions:** 0/0 matched
- **Missing functions:** _none_
- **Types:** 0/0 matched
- **Missing types:** _none_

### 25. unicode_tables.case_folding_simple

- **Target:** `casefoldingsimple.CaseFoldingSimple`
- **Similarity:** 1.00
- **Dependents:** 0
- **Priority Score:** 0.0
- **Functions:** 0/0 matched
- **Missing functions:** _none_
- **Types:** 0/0 matched
- **Missing types:** _none_

### 26. unicode_tables.general_category

- **Target:** `generalcategory.GeneralCategory`
- **Similarity:** 1.00
- **Dependents:** 0
- **Priority Score:** 0.0
- **Functions:** 0/0 matched
- **Missing functions:** _none_
- **Types:** 0/0 matched
- **Missing types:** _none_

### 27. unicode_tables.script_extension

- **Target:** `scriptextension.ScriptExtension`
- **Similarity:** 1.00
- **Dependents:** 0
- **Priority Score:** 0.0
- **Functions:** 0/0 matched
- **Missing functions:** _none_
- **Types:** 0/0 matched
- **Missing types:** _none_

### 28. unicode_tables.perl_space

- **Target:** `perlspace.PerlSpace`
- **Similarity:** 1.00
- **Dependents:** 0
- **Priority Score:** 0.0
- **Functions:** 0/0 matched
- **Missing functions:** _none_
- **Types:** 0/0 matched
- **Missing types:** _none_

### 29. unicode_tables.perl_decimal

- **Target:** `perldecimal.PerlDecimal`
- **Similarity:** 1.00
- **Dependents:** 0
- **Priority Score:** 0.0
- **Functions:** 0/0 matched
- **Missing functions:** _none_
- **Types:** 0/0 matched
- **Missing types:** _none_

### 30. unicode_tables.sentence_break

- **Target:** `sentencebreak.SentenceBreak`
- **Similarity:** 1.00
- **Dependents:** 0
- **Priority Score:** 0.0
- **Functions:** 0/0 matched
- **Missing functions:** _none_
- **Types:** 0/0 matched
- **Missing types:** _none_

### 31. unicode_tables.property_bool

- **Target:** `propertybool.PropertyBool`
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
./ast_distance --init-tasks ../../tmp/regex-syntax/src rust ../../src/commonMain kotlin tasks.json ../../AGENTS.md

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
| `unicode_tables.mod` | `unicodetables.Mod` | 0 | `unicode_tables/mod.rs` | `unicodetables/Mod.kt` |

