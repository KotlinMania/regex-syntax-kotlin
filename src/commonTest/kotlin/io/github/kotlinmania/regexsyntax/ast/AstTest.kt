// port-lint: tests src/ast/mod.rs
package io.github.kotlinmania.regexsyntax.ast

import kotlin.test.Test
import kotlin.test.assertFalse

class AstTest {
    @Test
    fun noStackOverflowOnDrop() {
        fun span(): Span = Span.splat(Position.new(0, 0, 0))

        var ast = Ast.empty(span())
        for (i in 0 until 200) {
            ast = Ast.group(Group(
                span = span(),
                kind = GroupKind.CaptureIndex(i.toUInt()),
                ast = ast,
            ))
        }
        assertFalse(ast.isEmpty())
    }
}
