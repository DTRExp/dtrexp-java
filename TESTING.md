# Testing

The package is tested at three levels: the conformance vectors (`test/resources/vectors.json`, the behavioral contract), unit tests for everything the vectors don't reach (`UnitTests.java`: public surface, every parse-error branch, evaluation corners, static-analysis boundaries), and mutation testing with [PIT](https://pitest.org).

The library itself is zero-dependency and builds with plain `javac`. Coverage (JaCoCo) and mutation (PIT + JUnit) tooling is optional and lives in the gitignored `_tools/` directory; the test suite itself uses no framework: two `main()` programs that exit nonzero on failure.

## Commands

```sh
./run.sh          # compile, run the conformance vectors + unit tests
./run.sh cover    # the same under JaCoCo; fails unless coverage is 100%
./run.sh mutate   # PIT mutation testing; writes _tools/pit-report/
```

Each mode prints fetch instructions for its jars on first use (`cover`: the JaCoCo agent + CLI; `mutate`: PIT 1.25.7, the JUnit 5.14/1.14 platform, and commons-text/lang3 for PIT's CSV writer).

`mutate` bridges PIT to the plain-main suite through `PitSuite.java`, a two-test JUnit class asserting the harnesses' `run()` methods report zero failures. The default build excludes it, so the shipped tree still needs nothing beyond `javac`.

Two environment notes, learned the hard way: PIT's CSV listener genuinely requires `commons-text` (without it the analysis thread dies at the first written result and every minion after it appears to crash), and on macOS the run pins `java.io.tmpdir` to `_tools/pit-tmp/` because the system purges `/var/folders/…/T` mid-run, deleting PIT's extracted javaagent out from under its minions.

## Coverage: 100% Lines and Branches

`./run.sh cover` enforces **100% line and branch coverage** of the shipped classes (the test harness itself is excluded from measurement), driven by behavioral assertions; every case asserts a spec-level outcome, never that a line merely executed.

## Mutation Testing

Full run: **638 mutants: 627 caught, 11 equivalent (justified below), 0 unjustified survivors.**

Seven of the caught are non-termination kills: mutants that turn bounded loops into infinite or allocation-unbounded ones (the parser's space-skipper, list-building loops). A suite that can never complete has detected the mutant; PIT counts these toward its kill score, as Stryker does with `TimedOut`.

Every other mutant changes observable behavior and is killed by a test. The survivors below are behaviorally equivalent to the original; each carries its proof, because "no test could distinguish it" is a claim that has to be earned per mutant.

### Justified Equivalent Survivors (11)

PIT has no inline suppression mechanism, so equivalents are documented here.

| Mutant | Site | Why Equivalent |
| --- | --- | --- |
| PrimitiveReturns `Ast.java:165` | `scopeOfDay` `return 'M'` → `0` | Every consumer switches on the result with cases only for `'Q'`/`'Y'` and month behavior as `default`; `0` takes `default` exactly as `'M'` does. |
| PrimitiveReturns `Ast.java:173` | the fallback `return 'M'` → `0` | Same switch argument. |
| ConditionalsBoundary `Evaluator.java:219` | `ordN > 0` → `>=` | Ordinal zero is a parse error ("ordinal zero"); the operators differ only at 0. |
| VoidMethodCall `Parser.java:47` | drop `skipSpaces()` in `parseAll` | `parseExpression` only returns with the cursor at `'|'` or end of input (its loop starts with `skipSpaces` and breaks only there), so the call is always a no-op. |
| ConditionalsBoundary `Parser.java:192` | `start >= 0` → `> 0` | The flag only feeds `wrap = literalBoth && start > end`; at `start == 0`, `start > end` with `end >= 0` is impossible, so wrap is false either way. |
| ConditionalsBoundary `Parser.java:337` | T-range wrap `>` → `>=` | Equal endpoints are rejected two lines earlier ("would cover nothing"); the operands are never equal. |
| ConditionalsBoundary `Parser.java:490` | `take < 0` → `<=` | `take ∈ {6, 4, -1}` by construction on the previous line; never 0. |
| ConditionalsBoundary `Parser.java:611` | `v < 0` → `<=` | A day value of zero never reaches `checkDayValue`: `parseIntValue` rejects `0` on every 1-based domain first. |
| NegateConditionals `StaticChecks.java:245` | star branch of `staticSet` yields an empty set | An empty set from a star selector is observably identical to the full domain in every consumer: the M∩Q disjointness check early-returns quiet on empty (and a star can never be disjoint), and `daySizes` maps empty month/quarter sets to defaults equal to the full-domain enumerations (`{28,29,30,31}` / `{90,91,92}`). |
| EmptyObjectReturns `StaticChecks.java:248` | same site, same empty set | Same argument. |
| ConditionalsBoundary `StaticChecks.java:302` | year-range span `> 1000` → `>=` | Differs only for a range of exactly 1001 consecutive years, which always contain leap, common, 52-week and 53-week years, so the enumerated size sets equal the open-years fallbacks and warnings are identical (warnings are `concreteYears`' only consumer; month/quarter day sizes ignore years entirely). |
