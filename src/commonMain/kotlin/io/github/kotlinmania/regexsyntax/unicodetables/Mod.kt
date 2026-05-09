// port-lint: source src/unicode_tables/mod.rs
package io.github.kotlinmania.regexsyntax.unicodetables

/*
 * Copyright (c) The rust-lang regex contributors.
 * Licensed under either of Apache-2.0 OR MIT.
 */

/*
 * Upstream `mod.rs` contents (tracking):
 *
 * - `pub mod age;`
 * - `pub mod case_folding_simple;`
 * - `pub mod general_category;`
 * - `pub mod grapheme_cluster_break;`
 * - `pub mod perl_decimal;`
 * - `pub mod perl_space;`
 * - `pub mod perl_word;`
 * - `pub mod property_bool;`
 * - `pub mod property_names;`
 * - `pub mod property_values;`
 * - `pub mod script;`
 * - `pub mod script_extension;`
 * - `pub mod sentence_break;`
 * - `pub mod word_break;`
 *
 * Kotlin note:
 * - The tables live in subpackages under `io.github.kotlinmania.regexsyntax.unicodetables`.
 * - Upstream `#[cfg(feature = "...")]` gating is not preserved in this Kotlin port.
 */

