package io.github.kotlinmania.regexsyntax.ast

import kotlin.test.Test

// Defines an abstract syntax for regular expressions.

// Upstream `ast_size` verifies `core::mem::size_of::<Ast>() <= 2 * size_of::<usize>()` for Rust enum layout compactness; KMP JVM/Native/JS object sizes are runtime-managed.

public class AstModInlineTests {
    // KMP commonTest has no portable 16 KiB OS thread spawner; verify iterative Ast.drop() handles deep nesting directly.
    @Test
    public fun noStackOverflowOnDrop() {
        val span = { Span.splat(Position.new(0, 0, 0)) }
        var ast = Ast.empty(span())
        for (i in 0u until 200u) {
            ast =
                Ast.group(
                    Group(
                        span = span(),
                        kind = GroupKind.CaptureIndex(i),
                        ast = ast,
                    ),
                )
        }
        check(!ast.isEmpty())
        ast.drop()
    }
}
