# UTF-8 sequence ordering parity

The upstream regex-syntax 0.8.10 Utf8Sequence enum derives Ord and PartialOrd.
Its One, Two, Three and Four variants sort in declaration order before any
byte-range fields are considered. The existing Kotlin compareTo compared byte
ranges first and used length only as a tie-breaker, changing cross-variant
ordering. The repair uses each variant's unique length as its discriminant,
then preserves the existing unsigned lexicographic range comparison.

Fresh translator comparison output is in
`build/rust-kotlin-utf8/Utf8Review.kt`, generated from the original utf8.rs
using this repository's gradle.properties. It does not emit the derived
comparison implementation. This is a manual library repair based on the
original enum derive, not a generator change. The three new Kotlin tests cover
all variant pairs with adversarial bytes, unsigned range ordering, real UTF-8
ranges and reversed-range values. Two tests failed before the repair; the
same-variant test already passed.

Run the actual upstream oracle (no downloaded dependencies):

```sh
rustc --edition=2021 --test -O verification/utf8-order-oracle.rs -o build/rust-kotlin-utf8/oracle
build/rust-kotlin-utf8/oracle
```

The harness includes the unchanged upstream utf8.rs directly. Seven tests pass:
three focused ordering tests and four original upstream tests, including
surrogate rejection and all Unicode scalar values. The harness reports one
ordinary dead-code warning for unused upstream reset; it is not suppressed.

## Reversal contract repair

The subsequent reversal pass restores upstream reverse's in-place mutation
and unit return. Three new regressions failed against the original functional
implementation; the unchanged Rust oracle now includes mutation/unit/double
reversal, copy independence and prefix matching (ten total tests).
Fresh comparison output is in build/rust-kotlin-utf8-reverse/Utf8Review.kt.
It emits a unit-returning reverse, although its List mutation calls still
need adaptation; no generator repair is claimed.

The three multi-range variants now use private setters for their List
properties. They remain publicly read-only; reversal replaces the sequence's
list in reverse order. Explicit copy, component1, equals, hashCode and debug
methods preserve the previous data-class API. The variants are ordinary
classes now; Kotlin reflection's isData flag changes. The fixed-size checks
remain at construction/copy and cannot be bypassed via a new public setter.
The single-range data class remains unchanged. Existing test callers now call
reverse without assigning a returned sequence, matching upstream.

Kotlin reference identity, aliased input Lists and borrowed-list views still
do not enforce Rust Copy or shared/exclusive borrow rules. Explicit copy is
independent under this reversal operation because reversal replaces the list;
no general ownership equivalence is claimed. Existing variant debug output
is retained, rather than silently changing another formatting contract.

A read-only workspace commonMain search for Utf8Sequence/Utf8Sequences found
only the defining source file; no external production caller was changed.
This is not a downstream compilation result.

Validation: all 156 JVM and 156 macOS ARM64 tests passed. All seven actual
Rust harness tests passed. Global static checks are not green: Detekt reports
four unchanged-source findings (EmptyWhileBlock at ast/parse/Parse.kt:1935;
IteratorNotThrowingNoSuchElementException at hir/Hir.kt:999 and :1212 and
hir/interval/Interval.kt:418). Global ktlint reports 571 commonMain and 21
commonTest violations. No violation points to a changed line; all ten
reported Utf8.kt locations map to unchanged lines of checkpoint 014ed19.
The new ordering test has no ktlint finding. Baseline comparison evidence is
in build/rust-kotlin-utf8/static-baseline-comparison.txt. No checks were disabled
or suppressed. No dependency pins changed. Other targets and downstream
builds are unverified. Reversal was unresolved at the ordering checkpoint; the subsequent pass above resolves its mutation/return contract.

Reversal validation: all 159 JVM tests, 159 macOS ARM64 tests and ten actual
upstream Rust tests passed. Three new Kotlin reversal tests failed before
repair. Static checks still report the same four Detekt findings and 571
commonMain / 21 commonTest ktlint findings. All ten flagged Utf8.kt lines map
to unchanged lines in 97df02a; the new reversal test has no lint finding.
Evidence: build/rust-kotlin-utf8-reverse/static-baseline-comparison.json.
No Unicode tables/version, dependency pins or generator files changed.
Other targets and downstream builds remain unverified.
