import Testing
import RegexSyntax

@Suite("RegexSyntax Swift Export Tests")
struct RegexSyntaxExportTests {
    @Test("Verify RegexSyntax module imports and can be referenced")
    func smokeTest() {
        #expect(true)
    }
}
