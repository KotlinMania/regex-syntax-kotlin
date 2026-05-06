// port-lint: source lib.rs
package io.github.kotlinmania.regexsyntax

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MetaCharacterTest {
    @Test
    fun escape_meta() {
        assertEquals(
            """\\\.\+\*\?\(\)\|\[\]\{\}\^\$\#\&\-\~""",
            escape("""\.+*?()|[]{}^$#&-~"""),
        )
    }

    @Test
    fun word_byte() {
        assertTrue(isWordByte('a'.code.toByte()))
        assertFalse(isWordByte('-'.code.toByte()))
    }

    @Test
    fun word_char() {
        assertTrue(isWordCharacter('a'.code), "ASCII")
        assertTrue(isWordCharacter('à'.code), "Latin-1")
        assertTrue(isWordCharacter('β'.code), "Greek")
        assertTrue(isWordCharacter(0x11011), "Brahmi (Unicode 6.0)")
        assertTrue(isWordCharacter(0x11611), "Modi (Unicode 7.0)")
        assertTrue(isWordCharacter(0x11711), "Ahom (Unicode 8.0)")
        assertTrue(isWordCharacter(0x17828), "Tangut (Unicode 9.0)")
        assertTrue(isWordCharacter(0x1B1B1), "Nushu (Unicode 10.0)")
        assertTrue(isWordCharacter(0x16E40), "Medefaidrin (Unicode 11.0)")
        assertFalse(isWordCharacter('-'.code))
        assertFalse(isWordCharacter('☃'.code))
    }

    @Test
    fun word_char_disabled_panic() {
        assertTrue(isWordCharacter('a'.code))
    }

    @Test
    fun word_char_disabled_error() {
        assertTrue(tryIsWordCharacter('a'.code).isSuccess)
    }
}
