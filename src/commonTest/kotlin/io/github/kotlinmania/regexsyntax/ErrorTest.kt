// port-lint: source error.rs
package io.github.kotlinmania.regexsyntax

import io.github.kotlinmania.regexsyntax.ast.parse.AstException
import io.github.kotlinmania.regexsyntax.ast.parse.Parser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

class ErrorTest {
    private fun assert_panic_message(pattern: String, expectedMsg: String) {
        val result = Parser().parse(pattern)
        val err = result.exceptionOrNull()
        if (err == null) {
            fail("regex should not have parsed")
        }
        val astErr = err as AstException
        assertEquals(expectedMsg.trimIndent().trim(), astErr.err.toString())
    }

    // See: https://github.com/rust-lang/regex/issues/464
    @Test
    fun regression_464() {
        val result = Parser().parse("a{\n")
        val err = result.exceptionOrNull() as AstException
        // This test checks that the error formatter doesn't panic.
        assertTrue(err.err.toString().isNotEmpty())
    }

    // See: https://github.com/rust-lang/regex/issues/545
    @Test
    fun repetition_quantifier_expects_a_valid_decimal() {
        assert_panic_message(
            "\\\\u{[^}]*}",
            """
            regex parse error:
                \\u{[^}]*}
                    ^
            error: repetition quantifier expects a valid decimal
            """,
        )
    }
}
