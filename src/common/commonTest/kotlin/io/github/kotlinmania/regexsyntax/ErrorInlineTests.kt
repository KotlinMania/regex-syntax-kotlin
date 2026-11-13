// port-lint: tests error.rs
package io.github.kotlinmania.regexsyntax.error

import io.github.kotlinmania.regexsyntax.ast.parse.Parser

public fun assertPanicMessage(pattern: String, expectedMsg: String) {
    val result = Parser.new().parse(pattern)
    if (result.isSuccess) {
        error("regex should not have parsed")
    } else {
        val err = result.exceptionOrNull()!!
        check(err.toString() == expectedMsg.trim())
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
