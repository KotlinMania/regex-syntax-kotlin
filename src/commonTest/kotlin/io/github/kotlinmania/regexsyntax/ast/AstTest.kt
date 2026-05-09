// port-lint: source ast/mod.rs
package io.github.kotlinmania.regexsyntax.ast

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AstTest {
    @Test
    fun no_stack_overflow_on_drop() {
        // Rust uses a thread with an explicit stack size to test that its
        // destructor for Ast can handle arbitrarily sized expressions in
        // constant stack space. Kotlin commonTest has no portable
        // explicit-stack thread API, so this keeps the same nested expression
        // construction in common code.
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

    // This tests that our `Ast` has a reasonable size. This isn't a hard rule
    // and it can be increased if given a good enough reason. But this test
    // exists because the size of `Ast` was at one point over 200 bytes on a
    // 64-bit target. Wow.
    @Test
    fun ast_size() {
        val max = 1
        val size = ast_variants().maxOf { ast_payload_words(it) }
        assertTrue(
            size <= max,
            "Ast size of $size payload words is bigger than suggested max $max",
        )
    }

    private fun span(): Span = Span.splat(Position.new(0, 0, 0))

    private fun ast_variants(): List<Ast> {
        val empty = Ast.empty(span())
        val flags = Flags(span(), mutableListOf())
        val setFlags = SetFlags(span(), flags)
        val literal = Literal(span(), LiteralKind.Verbatim, 'a'.code)
        val classPerl = ClassPerl(span(), ClassPerlKind.Digit, false)
        val classUnicode = ClassUnicode(span(), false, ClassUnicodeKind.Named("L"))
        val classSet = ClassSet.Item(ClassSetItem.Empty(span()))
        return listOf(
            Ast.empty(span()),
            Ast.flags(setFlags),
            Ast.literal(literal),
            Ast.dot(span()),
            Ast.assertion(Assertion(span(), AssertionKind.StartText)),
            Ast.classUnicode(classUnicode),
            Ast.classPerl(classPerl),
            Ast.classBracketed(ClassBracketed(span(), false, classSet)),
            Ast.repetition(Repetition(span(), RepetitionOp(span(), RepetitionKind.ZeroOrMore), true, empty)),
            Ast.group(Group(span(), GroupKind.CaptureIndex(0u), empty)),
            Ast.alternation(Alternation(span(), mutableListOf(empty))),
            Ast.concat(Concat(span(), mutableListOf(empty))),
        )
    }

    private fun ast_payload_words(ast: Ast): Int = when (ast) {
        is Ast.Empty -> 1
        is Ast.Flags -> 1
        is Ast.Literal -> 1
        is Ast.Dot -> 1
        is Ast.Assertion -> 1
        is Ast.ClassUnicode -> 1
        is Ast.ClassPerl -> 1
        is Ast.ClassBracketed -> 1
        is Ast.Repetition -> 1
        is Ast.Group -> 1
        is Ast.Alternation -> 1
        is Ast.Concat -> 1
    }
}
