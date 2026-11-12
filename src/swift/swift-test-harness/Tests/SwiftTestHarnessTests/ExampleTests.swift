import Testing
import RegexSyntax

@Suite("RegexSyntax escaping")
struct RegexSyntaxEscapingTests {
    @Test("Regex metacharacters are escaped without changing plain text")
    func escapesMetacharacters() {
        #expect(escape(text: "a+b(c).") == #"a\+b\(c\)\."#)
        #expect(escape(text: "plain text") == "plain text")
        #expect(escape(text: "") == "")
    }
}
