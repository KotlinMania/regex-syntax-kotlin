import Testing
import RegexSyntax

@Suite("RegexSyntax character classification")
struct RegexSyntaxExportTests {
    @Test("Metacharacters and optional escapes have distinct behavior")
    func classifiesCharacters() {
        #expect(isMetaCharacter(c: 43))
        #expect(!isMetaCharacter(c: 37))
        #expect(isEscapeableCharacter(c: 37))
        #expect(!isEscapeableCharacter(c: 101))
        #expect(!isEscapeableCharacter(c: 0x2603))
    }
}
