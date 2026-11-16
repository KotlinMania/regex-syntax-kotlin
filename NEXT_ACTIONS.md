# Immediate Actions - High-Value Files

Based on AST analysis, here are the concrete next steps.

## Summary

- **Files Present:** 27/33 (81.8%)
- **Function parity:** 412/670 matched (target 706) — 61.5%
- **Class/type parity:** 58/71 matched (target 120) — 81.7%
- **Combined symbol parity:** 470/741 matched (target 826) — 63.4%
- **Average inline-code cosine:** 0.66 (function body across 27 matched files)
- **Average documentation cosine:** 0.47 (doc text across 27 matched files)
- **Cheat-zeroed Files:** 0
- **Critical Issues:** 13 files with <0.60 function similarity
- **Needs Review:** 0 files with 0.60-0.84 function similarity
- **Excellent:** 14 files with >=0.85 function similarity

## Priority 1: Fix Incomplete High-Dependency Files

No incomplete high-dependency files detected.

## Priority 2: Port Missing High-Value Files

Critical missing files (>10 dependencies):

No missing high-value files detected.

## Detailed Work Items

Every matched file is listed below with function and type symbol parity.

### 1. unicode

- **Target:** `unicode.Unicode`
- **Similarity:** 0.08
- **Dependents:** 1
- **Priority Score:** 1407709.1
- **Functions:** 29/69 matched (target 44)
- **Missing functions:** `class`, `imp`, `imp`, `imp`, `imp`, `imp`, `imp`, `imp`, `imp`, `imp`, `imp`, `imp`, `imp`, `imp`, `imp`, `imp`, `imp`, `imp`, `imp`, `imp`, `imp`, `imp`, `imp`, `imp`, `imp`, `imp`, `imp`, `imp`, `imp`, `imp`, `imp`, `simple_fold_ok`, `contains_case_map`, `simple_fold_k`, `simple_fold_a`, `simple_fold_disabled`, `range_contains`, `regression_466`, `sym_normalize`, `valid_utf8_symbolic`
- **Types:** 8/8 matched (target 17)
- **Missing types:** _none_
- **Tests:** 0/9 matched

### 2. parser

- **Target:** `parser.Parser`
- **Similarity:** 0.45
- **Dependents:** 1
- **Priority Score:** 1001805.5
- **Functions:** 16/16 matched (target 17)
- **Missing functions:** _none_
- **Types:** 2/2 matched
- **Missing types:** _none_

### 3. unicode_tables.property_values

- **Target:** `propertyvalues.PropertyValues`
- **Similarity:** 1.00
- **Dependents:** 1
- **Priority Score:** 1000000.0
- **Functions:** 0/0 matched
- **Missing functions:** _none_
- **Types:** 0/0 matched
- **Missing types:** _none_

### 4. unicode_tables.property_names

- **Target:** `propertynames.PropertyNames`
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

### 6. unicode_tables.age

- **Target:** `age.Age`
- **Similarity:** 1.00
- **Dependents:** 1
- **Priority Score:** 1000000.0
- **Functions:** 0/0 matched
- **Missing functions:** _none_
- **Types:** 0/0 matched
- **Missing types:** _none_

### 7. hir.translate

- **Target:** `translate.Translate`
- **Similarity:** 0.32
- **Dependents:** 0
- **Priority Score:** 906006.8
- **Functions:** 65/151 matched
- **Missing functions:** `eq`, `eq`, `parse`, `t`, `t_err`, `t_bytes`, `props`, `props_bytes`, `hir_lit`, `hir_blit`, `hir_capture`, `hir_capture_name`, `hir_quest`, `hir_star`, `hir_plus`, `hir_range`, `hir_alt`, `hir_cat`, `hir_uclass_query`, `hir_uclass_perl_word`, `hir_ascii_uclass`, `hir_ascii_bclass`, `hir_uclass`, `hir_bclass`, `hir_case_fold`, `hir_negate`, `uclass`, `bclass`, `class_case_fold`, `class_negate`, `hir_union`, `hir_difference`, `hir_look`, `empty`, `literal`, `literal_case_insensitive`, `dot`, `assertions`, `group`, `line_anchors`, `flags`, `escape`, `repetition`, `cat_alt`, `cat_class_flattened`, `class_ascii`, `class_ascii_multiple`, `class_perl_unicode`, `class_perl_ascii`, `class_perl_word_disabled`, `class_perl_space_disabled`, `class_perl_digit_disabled`, `class_unicode_gencat`, `class_unicode_gencat_disabled`, `class_unicode_script`, `class_unicode_script_disabled`, `class_unicode_age`, `class_unicode_any_empty`, `class_unicode_age_disabled`, `class_bracketed`, `class_bracketed_union`, `class_bracketed_nested`, `class_bracketed_intersect`, `class_bracketed_intersect_negate`, `class_bracketed_difference`, `class_bracketed_symmetric_difference`, `ignore_whitespace`, `analysis_is_utf8`, `analysis_captures_len`, `analysis_static_captures_len`, `analysis_is_all_assertions`, `analysis_look_set_prefix_any`, `analysis_is_anchored`, `analysis_is_any_anchored`, `analysis_can_empty`, `analysis_is_literal`, `analysis_is_alternation_literal`, `smart_repetition`, `smart_concat`, `smart_alternation`, `regression_alt_empty_concat`, `regression_empty_alt`, `regression_singleton_alt`, `regression_fuzz_match`, `regression_fuzz_difference1`, `regression_fuzz_char_decrement1`
- **Types:** 6/9 matched (target 16)
- **Missing types:** `Result`, `Output`, `Err`
- **Tests:** 0/86 matched
- **Lint issues:** 2

### 8. ast.parse

- **Target:** `parse.Parse`
- **Similarity:** 0.33
- **Dependents:** 0
- **Priority Score:** 675906.7
- **Functions:** 85/148 matched (target 155)
- **Missing functions:** `eq`, `eq`, `s`, `parser`, `parser_octal`, `parser_empty_min_range`, `parser_nest_limit`, `parser_ignore_whitespace`, `nspan`, `npos`, `span`, `span_range`, `lit`, `meta_lit`, `lit_with`, `concat`, `concat_with`, `alt`, `group`, `flag_set`, `parse_nest_limit`, `parse_comments`, `parse_holistic`, `parse_ignore_whitespace`, `parse_newlines`, `parse_uncounted_repetition`, `parse_counted_repetition`, `parse_alternate`, `parse_unsupported_lookaround`, `parse_group`, `parse_capture_name`, `parse_flags`, `parse_flag`, `parse_primitive_non_escape`, `parse_escape`, `parse_unsupported_backreference`, `parse_octal`, `parse_hex_two`, `parse_hex_four`, `parse_hex_eight`, `parse_hex_brace`, `parse_decimal`, `parse_set_class`, `union`, `intersection`, `difference`, `symdifference`, `itemset`, `item_ascii`, `item_unicode`, `item_perl`, `item_bracket`, `lit`, `empty`, `range`, `alnum`, `lower`, `parse_set_class_open`, `maybe_parse_ascii_class`, `parse_unicode_class`, `parse_perl_class`, `regression_454_nest_too_big`, `regression_455_trailing_dash_ignore_whitespace`
- **Types:** 8/11 matched (target 19)
- **Missing types:** `Result`, `Output`, `Err`
- **Tests:** 0/63 matched
- **Lint issues:** 2

### 9. hir.literal

- **Target:** `literal.Literal`
- **Similarity:** 0.34
- **Dependents:** 0
- **Priority Score:** 321406.6
- **Functions:** 77/108 matched (target 131)
- **Missing functions:** `from`, `parse`, `prefixes`, `suffixes`, `e`, `E`, `I`, `seq`, `infinite`, `inexact`, `exact`, `opt`, `literal`, `class`, `look`, `repetition`, `concat`, `alternation`, `impossible`, `anything`, `anything_small_limits`, `prefixes`, `suffixes`, `e`, `empty`, `odds_and_ends`, `holmes`, `holmes_alt`, `crazy_repeats`, `huge`, `optimize`
- **Types:** 6/6 matched (target 10)
- **Missing types:** _none_
- **Tests:** 0/30 matched

### 10. hir.print

- **Target:** `print.Print`
- **Similarity:** 0.38
- **Dependents:** 0
- **Priority Score:** 153006.2
- **Functions:** 12/25 matched (target 26)
- **Missing functions:** `roundtrip`, `roundtrip_bytes`, `roundtrip_with`, `print_literal`, `print_class`, `print_anchor`, `print_word_boundary`, `print_repetition`, `print_group`, `print_alternation`, `regression_repetition_concat`, `regression_repetition_alternation`, `regression_alternation_concat`
- **Types:** 3/5 matched (target 4)
- **Missing types:** `Output`, `Err`
- **Tests:** 0/13 matched

### 11. ast.print

- **Target:** `commonMain.kotlin.io.github.kotlinmania.regexsyntax.ast.print.Print`
- **Similarity:** 0.38
- **Dependents:** 0
- **Priority Score:** 134206.2
- **Functions:** 26/37 matched (target 43)
- **Missing functions:** `roundtrip`, `roundtrip_with`, `print_literal`, `print_dot`, `print_concat`, `print_alternation`, `print_assertion`, `print_repetition`, `print_flags`, `print_group`, `print_class`
- **Types:** 3/5 matched (target 4)
- **Missing types:** `Output`, `Err`
- **Tests:** 0/11 matched
- **Lint issues:** 2

### 12. utf8

- **Target:** `utf8.Utf8`
- **Similarity:** 0.32
- **Dependents:** 0
- **Priority Score:** 93406.8
- **Functions:** 21/28 matched (target 39)
- **Missing functions:** `rutf8`, `never_accepts_surrogate_codepoints`, `codepoints_no_surrogates`, `single_codepoint_one_sequence`, `bmp`, `reverse`, `encode_surrogate`
- **Types:** 4/6 matched (target 9)
- **Missing types:** `IntoIter`, `Item`
- **Tests:** 0/7 matched

### 13. error

- **Target:** `error.Error`
- **Similarity:** 0.30
- **Dependents:** 0
- **Priority Score:** 61907.0
- **Functions:** 10/16 matched (target 19)
- **Missing functions:** `from`, `from`, `repeat_char`, `assert_panic_message`, `regression_464`, `repetition_quantifier_expects_a_valid_decimal`
- **Types:** 3/3 matched (target 6)
- **Missing types:** _none_
- **Tests:** 0/3 matched

### 14. hir.interval

- **Target:** `interval.Interval`
- **Similarity:** 0.21
- **Dependents:** 0
- **Priority Score:** 23607.9
- **Functions:** 30/31 matched (target 35)
- **Missing functions:** `eq`
- **Types:** 4/5 matched (target 6)
- **Missing types:** `Item`

### 15. ast.visitor

- **Target:** `commonMain.kotlin.io.github.kotlinmania.regexsyntax.ast.visitor.Visitor`
- **Similarity:** 0.21
- **Dependents:** 0
- **Priority Score:** 3107.9
- **Functions:** 26/26 matched
- **Missing functions:** _none_
- **Types:** 5/5 matched (target 15)
- **Missing types:** _none_

### 16. hir.visitor

- **Target:** `visitor.Visitor`
- **Similarity:** 0.18
- **Dependents:** 0
- **Priority Score:** 1408.2
- **Functions:** 11/11 matched
- **Missing functions:** _none_
- **Types:** 3/3 matched (target 7)
- **Missing types:** _none_

### 17. debug

- **Target:** `debug.Debug`
- **Similarity:** 0.19
- **Dependents:** 0
- **Priority Score:** 608.1
- **Functions:** 4/4 matched (target 9)
- **Missing functions:** _none_
- **Types:** 2/2 matched
- **Missing types:** _none_

### 18. either

- **Target:** `either.Either`
- **Similarity:** 1.00
- **Dependents:** 0
- **Priority Score:** 100.0
- **Functions:** 0/0 matched
- **Missing functions:** _none_
- **Types:** 1/1 matched (target 3)
- **Missing types:** _none_

### 19. unicode_tables.script

- **Target:** `script.Script`
- **Similarity:** 1.00
- **Dependents:** 0
- **Priority Score:** 0.0
- **Functions:** 0/0 matched
- **Missing functions:** _none_
- **Types:** 0/0 matched
- **Missing types:** _none_

### 20. unicode_tables.word_break

- **Target:** `wordbreak.WordBreak`
- **Similarity:** 1.00
- **Dependents:** 0
- **Priority Score:** 0.0
- **Functions:** 0/0 matched
- **Missing functions:** _none_
- **Types:** 0/0 matched
- **Missing types:** _none_

### 21. unicode_tables.property_bool

- **Target:** `propertybool.PropertyBool`
- **Similarity:** 1.00
- **Dependents:** 0
- **Priority Score:** 0.0
- **Functions:** 0/0 matched
- **Missing functions:** _none_
- **Types:** 0/0 matched
- **Missing types:** _none_

### 22. unicode_tables.sentence_break

- **Target:** `sentencebreak.SentenceBreak`
- **Similarity:** 1.00
- **Dependents:** 0
- **Priority Score:** 0.0
- **Functions:** 0/0 matched
- **Missing functions:** _none_
- **Types:** 0/0 matched
- **Missing types:** _none_

### 23. rank

- **Target:** `rank.Rank`
- **Similarity:** 1.00
- **Dependents:** 0
- **Priority Score:** 0.0
- **Functions:** 0/0 matched
- **Missing functions:** _none_
- **Types:** 0/0 matched
- **Missing types:** _none_

### 24. unicode_tables.general_category

- **Target:** `generalcategory.GeneralCategory`
- **Similarity:** 1.00
- **Dependents:** 0
- **Priority Score:** 0.0
- **Functions:** 0/0 matched
- **Missing functions:** _none_
- **Types:** 0/0 matched
- **Missing types:** _none_

### 25. unicode_tables.script_extension

- **Target:** `scriptextension.ScriptExtension`
- **Similarity:** 1.00
- **Dependents:** 0
- **Priority Score:** 0.0
- **Functions:** 0/0 matched
- **Missing functions:** _none_
- **Types:** 0/0 matched
- **Missing types:** _none_

### 26. unicode_tables.case_folding_simple

- **Target:** `casefoldingsimple.CaseFoldingSimple`
- **Similarity:** 1.00
- **Dependents:** 0
- **Priority Score:** 0.0
- **Functions:** 0/0 matched
- **Missing functions:** _none_
- **Types:** 0/0 matched
- **Missing types:** _none_

### 27. unicode_tables.grapheme_cluster_break

- **Target:** `graphemeclusterbreak.GraphemeClusterBreak`
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

## Reexport / Wiring Modules

These files match `reexport_modules` patterns in `.ast_distance_config.json`. They are filtered out of
normal priority and missing-file ladders because they are wiring
modules, not direct logic ports. Consult them for call-site routing;
do not treat them as the next implementation target by default.

### Missing

| Source | Expected target | Deps | Source path | Expected path |
|--------|-----------------|------|-------------|---------------|
| `ast.mod` | `ast.Mod` | 0 | `ast/mod.rs` | `ast/Mod.kt` |
| `hir.mod` | `hir.Mod` | 0 | `hir/mod.rs` | `hir/Mod.kt` |
| `lib` | `Lib` | 0 | `lib.rs` | `Lib.kt` |
| `unicode_tables.mod` | `unicodetables.Mod` | 0 | `unicode_tables/mod.rs` | `unicodetables/Mod.kt` |

