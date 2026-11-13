package io.github.kotlinmania.regexsyntax.ast

import io.github.kotlinmania.rustlang.core.mem.ObjectSlotLayout
import io.github.kotlinmania.rustlang.core.mem.PrimitiveLayouts

// Defines an abstract syntax for regular expressions.

// This tests that our `Ast` has a reasonable size. This isn't a hard rule
// and it can be increased if given a good enough reason. But this test
// exists because the size of `Ast` was at one point over 200 bytes on a
// 64-bit target. Wow.
@kotlin.test.Test
public fun astSize() {
    val max = 2 * sizeOf<Int>(typeLayoutT = PrimitiveLayouts.USIZE64)
    val size = sizeOf<Ast>(typeLayoutT = ObjectSlotLayout<Ast>())
    check(size <= max) { "Ast size of $size bytes is bigger than suggested max $max" }
}
