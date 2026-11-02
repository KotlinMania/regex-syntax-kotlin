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

## Remaining reversal gap

Upstream reverse mutates the sequence and returns unit. The existing Kotlin
reverse returns a new sequence and leaves the original unchanged. Existing
Kotlin tests assign the result, unlike the upstream tests. This repair leaves
that mismatch explicit: a source-faithful correction must reconcile the
current public immutable List properties, data-class copy/equality behavior
and callers together. It must not merely discard the new value, mutate only
some list implementations, or claim Kotlin's list references enforce Rust
borrow/copy rules. This ordering repair makes no ownership equivalence claim.

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
builds are unverified. Reversal remains unresolved as described above.
