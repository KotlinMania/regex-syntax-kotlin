import Testing
import RegexSyntax

@Suite("RegexSyntax Swift Export Suite")
struct RegexSyntaxExportTests {
    @Test("Metacharacters, escapes, and word characters are classified accurately")
    func classifiesCharactersAndEscapes() {
        #expect(isMetaCharacter(c: 43))
        #expect(!isMetaCharacter(c: 37))
        #expect(isEscapeableCharacter(c: 37))
        #expect(!isEscapeableCharacter(c: 101))
        #expect(!isEscapeableCharacter(c: 0x2603))
        #expect(isWordCharacter(c: 97))
        #expect(!isWordCharacter(c: 32))
        #expect(escape(text: "a+b[c]") == "a\\+b\\[c\\]")
    }

    @Test("ParserBuilder, TranslatorBuilder, Hir, and Extractor are usable from Swift")
    func buildersAndLiteralExtractionWork() {
        let _ = io.github.kotlinmania.regexsyntax.parser.ParserBuilder.Companion.shared.new()
            .caseInsensitive(yes: true)
            .build()
        let _ = io.github.kotlinmania.regexsyntax.hir.translate.TranslatorBuilder.Companion.shared.new()
            .caseInsensitive(yes: true)
            .build()
        let hir = io.github.kotlinmania.regexsyntax.hir.Hir.Companion.shared.literal(lit: [97, 98, 99])
        #expect(hir.properties().minimumLen() == 3)
        #expect(hir.properties().isLiteral())

        let seq = io.github.kotlinmania.regexsyntax.hir.literal.Extractor.Companion.shared.new()
            .extract(hir: hir)
        #expect(seq.len() == 1)
    }
}
