// port-lint: tests error.rs
package io.github.kotlinmania.regexsyntax.error

import io.github.kotlinmania.regexsyntax.ast.parse.Parser
import io.github.kotlinmania.regexsyntax.unwrapErr
import io.github.kotlinmania.rustlang.core.result.Result

public fun assertPanicMessage(pattern: String, expectedMsg: String) {
    when (val result = Parser.new().parse(pattern)) {
        is Result.Ok -> error("regex should not have parsed")
        is Result.Err -> {
            val err = result.value
            check(err.toString() == expectedMsg.trim())
        }
    }
}

// See: https://github.com/rust-lang/regex/issues/464
@kotlin.test.Test
public fun regression464() {
    val err = Parser.new().parse("a{\n").unwrapErr()
    // This test checks that the error formatter doesn't panic.
    check(!err.toString().isEmpty())
}

// See: https://github.com/rust-lang/regex/issues/545
@kotlin.test.Test
public fun repetitionQuantifierExpectsAValidDecimal() {
    assertPanicMessage("\\\\u{[^}]*}", "\nregex parse error:\n    \\\\u{[^}]*}\n        ^\nerror: repetition quantifier expects a valid decimal\n")
}
