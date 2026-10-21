# Testing

The package is tested at three levels: the conformance vectors (`test/resources/vectors.json`, the behavioral contract), unit tests for everything the vectors don't reach (`UnitTests.java`: public surface, every parse-error branch, evaluation corners, static-analysis boundaries), and mutation testing with [PIT](https://pitest.org).

The library itself is zero-dependency and builds with plain `javac`. Coverage (JaCoCo) and mutation (PIT + JUnit) tooling is optional and lives in the gitignored `_tools/` directory; the test suite itself uses no framework: two `main()` programs that exit nonzero on failure.

## Commands

```sh
./run.sh          # compile, run the conformance vectors + unit tests
./run.sh cover    # the same under JaCoCo; fails unless coverage is 100%
./run.sh mutate   # PIT mutation testing; writes _tools/pit-report/ and test/mutation/mutations.xml
```

Each mode prints fetch instructions for its jars on first use (`cover`: the JaCoCo agent + CLI; `mutate`: PIT 1.25.7, the JUnit 5.14/1.14 platform, and commons-text/lang3 for PIT's CSV writer).

`mutate` bridges PIT to the plain-main suite through `PitSuite.java`, a two-test JUnit class asserting the harnesses' `run()` methods report zero failures. The default build excludes it, so the shipped tree still needs nothing beyond `javac`.

Two environment notes, learned the hard way: PIT's CSV listener genuinely requires `commons-text` (without it the analysis thread dies at the first written result and every minion after it appears to crash), and on macOS the run pins `java.io.tmpdir` to `_tools/pit-tmp/` because the system purges `/var/folders/…/T` mid-run, deleting PIT's extracted javaagent out from under its minions.

## Coverage: 100% Lines and Branches

`./run.sh cover` enforces **100% line and branch coverage** of the shipped classes (the test harness itself is excluded from measurement), driven by behavioral assertions; every case asserts a spec-level outcome, never that a line merely executed.

## Mutation Testing

Full run (2026-10-21, draft 3: error positions, the integer limit, zone names, the earliest error winning; rerun after the duplicate check became linear and a misplaced fraction a rule error): **692 mutants: 681 caught (one as a memory error, a mutant that never ends the digit run), 11 equivalent, 0 unjustified survivors**, after a bare `*` in a list became a rule error. Ten of the equivalents are still in the code and justified below; the eleventh went with the date-literal code it sat in.

`Parser` rerun on its own (2026-10-21, after the parser began reading on past a rule error so that the earliest error wins, a later grammar error included, with only D's domain waiting for the branch): **371 mutants: 367 caught, 4 survivors, the four `Parser` equivalents below.** Two more survived a first pass and were killed by new tests: a zero cadence period with a written duration (`20200101/0D/1H`), and the value an integer above the limit reads as, now the limit itself on the one return.

Seven of the caught are non-termination kills: mutants that turn bounded loops into infinite or allocation-unbounded ones (the parser's list-building and digit-reading loops, the static checks' year and stride walks, the cadence occurrence scan). A suite that can never complete has detected the mutant; PIT counts these toward its kill score, as Stryker does with `TimedOut`.

Every other mutant changes observable behavior and is killed by a test. The survivors below are behaviorally equivalent to the original; each carries its proof, because "no test could distinguish it" is a claim that has to be earned per mutant.

### Justified Equivalent Survivors (11)

PIT has no inline suppression mechanism, so equivalents are documented here.

| Mutant | Site | Why Equivalent |
| --- | --- | --- |
| NullReturnVals `Parser.java:189` | `parseIntItem` returns the `*:*` stand-in for a bare `*` in a list → `null` | The stand-in is built only after the bare `*` is recorded as a rule error, so the branch already holds an error, which is thrown at the branch's end before any of its items is read; checked by hand on `D1,*`, `D1,* M2`, `D1,*/2`, `D!1,*` and `E1,* D5`, which answer the same with either value. |
| PrimitiveReturns `Ast.java:165` | `scopeOfDay` `return 'M'` → `0` | Every consumer switches on the result with cases only for `'Q'`/`'Y'` and month behavior as `default`; `0` takes `default` exactly as `'M'` does. |
| PrimitiveReturns `Ast.java:173` | the fallback `return 'M'` → `0` | Same switch argument. |
| ConditionalsBoundary `Evaluator.java:220` | `ordN > 0` → `>=` | Ordinal zero is a parse error ("ordinal zero"); the operators differ only at 0. |
| VoidMethodCall `Parser.java:57` | drop `skipSpaces()` in `parseAll` | `parseExpression` only returns with the cursor at `'|'` or end of input (its loop starts with `skipSpaces` and breaks only there), so the call is always a no-op. |
| ConditionalsBoundary `Parser.java:208` | `start >= 0` → `> 0` | The flag only feeds `wrap = literalBoth && start > end`; at `start == 0`, `start > end` with `end >= 0` is impossible, so wrap is false either way. |
| ConditionalsBoundary `Parser.java:351` | T-range wrap `>` → `>=` | Equal endpoints are an error two lines earlier ("would cover nothing"), and the branch ends with it, so a range built from them is never evaluated. |
| ConditionalsBoundary `Parser.java:624` | `v < 0` → `<=` | At `v == 0` the second operand, `-v > max`, is false for every scope (`max` is at least 31); a zero day is the line above's error. |
| NegateConditionals `StaticChecks.java:247` | star branch of `staticSet` yields an empty set | An empty set from a star selector is observably identical to the full domain in every consumer: the M∩Q disjointness check early-returns quiet on empty (and a star can never be disjoint), and `daySizes` maps empty month/quarter sets to defaults equal to the full-domain enumerations (`{28,29,30,31}` / `{90,91,92}`). |
| EmptyObjectReturns `StaticChecks.java:250` | same site, same empty set | Same argument. |
| ConditionalsBoundary `StaticChecks.java:304` | year-range span `> 1000` → `>=` | Differs only for a range of exactly 1001 consecutive years, which always contain leap, common, 52-week and 53-week years, so the enumerated size sets equal the open-years fallbacks and warnings are identical (warnings are `concreteYears`' only consumer; month/quarter day sizes ignore years entirely). |
