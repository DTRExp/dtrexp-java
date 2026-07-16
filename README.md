<p align="center"><picture><source media="(prefers-color-scheme: dark)" srcset="./.github/logo-dark.svg"><img src="./.github/logo.svg" width="200" alt="dtrexp-java" /></picture></p>

# dtrexp-java

Java implementation of **[DTRExp](https://github.com/DTRExp/dtrexp)** (read: "**DTR Expression**") — a compact string expression for date-time ranges and recurrence, evaluated by **coverage** rather than enumeration.

```
T0900:1800 E1:5          Mon–Fri, 09:00–18:00
E7#-1 M4                 last Sunday of April, every year
20200106/10D             every 10 days from 2020-01-06 (cron can't say this)
M!7                      every month except July
```

Scope: **parsing, validation and coverage evaluation** (the spec's core interface). Rendering, description and RRULE export are out of scope; the [reference implementation][js] has them.

## Install

```xml
<dependency>
  <groupId>io.onury</groupId>
  <artifactId>dtrexp</artifactId>
  <version>1.0.1</version>
</dependency>
```

Gradle: `implementation("io.onury:dtrexp:1.0.1")`. Or skip the build tool entirely — zero dependencies, so building from source is just `./run.sh` (compiles the sources under `src/` and runs the conformance suite).

Pure Java 17+, zero dependencies (`java.time` for IANA zones).

## Usage

```java
import io.onury.dtrexp.DTRExp;
import java.time.Instant;
import java.time.ZoneId;

DTRExp dtr = DTRExp.parse("T0900:1800 E1:5");   // Mon–Fri, 09:00–18:00
// throws a positioned DTRExpParseException on a syntax or static-validity error

boolean open = dtr.covers(Instant.now(), "Europe/Berlin");
// —> true on a weekday, 09:00–18:00 Berlin local time
```

The zone is an evaluation parameter, never part of the expression. Three `covers` overloads take it three ways:

```java
dtr.covers(instant);                          // UTC — the default zone
dtr.covers(instant, ZoneId.of("Asia/Tokyo")); // a preloaded ZoneId
dtr.covers(instant, "Asia/Tokyo");            // an IANA identifier
```

Note that you parse **once** (at write/config time) and evaluate **many**. A `DTRExp` is immutable after `parse` and safe to share across threads; `covers` is a single calendar-field extraction followed by integer comparisons, with no occurrence iteration. `toString()` returns the source expression verbatim.

## Errors and Warnings

Both carry a **position**; the 0-based character offset into the source, rendered into the message as `(at N)`:

```java
DTRExp.parse("Y*/3");            // anchorless stride — throws DTRExpParseException
// e.position() points at the offending character

ValidationResult res = DTRExp.validate("D30 M2");   // never throws
res.valid();                     // true — it parses
res.warnings();                  // [DTRExpWarning{position=…, message="unsatisfiable …"}]
                                 // no February has 30 days
```

- `parse(s)` returns the expression or throws a `DTRExpParseException` (`position()` `int`, plus the message).
- `validate(s)` never throws; typo-shaped input comes back as data. Returns a `ValidationResult` record with `valid()` `boolean`, `errors()` (parsing stops at the first syntax error, so at most one `DTRExpParseException`) and `warnings()`.
- Warnings are the spec's [§9.1](https://github.com/DTRExp/dtrexp/blob/main/spec.md#91-the-existence-rule) unsatisfiability lint: expressions that parse but can never match. `DTRExp.warnings()` and `validate(s).warnings()` carry the same content. `DTRExpWarning` is a record of `(int position, String message)`.

## Conformance & Quality

- The test suite is driven by the shared [`vectors.json`][vectors] from the spec repo (draft 2.8): every coverage, rejection, warning and quiet vector, including the calendar traps (Feb 29 across 2000/2024/**2100**, `W53` existence, DST gap/overlap in `Europe/Berlin`). The vectors are vendored at `test/resources/vectors.json`; see [VECTORS.md][vectors-md] for how the suite works.
- The build compiles under `javac -Xlint:all -Werror`, so a warning fails it.
- Zero dependencies.

## Related Projects

- [**dtrexp** (spec)][spec] — the DTRExp specification (grammar, semantics, conformance vectors) this package implements.
- [**dtrexp-js**][js] — the reference implementation; adds `intersect`, `next`, `describe`, `toRRule` and canonicalization.
- [**dtrexp-py**][py] · [**dtrexp-go**][go] · [**dtrexp-swift**][swift] · [**dtrexp-rs**][rs] — the other ports; same core interface.
- [**dtrexp-wasm**][wasm] — the Rust core compiled to WebAssembly for JS hosts.

## License

© 2026, Onur Yıldırım. [**MIT**](LICENSE) License.

[spec]: https://github.com/DTRExp/dtrexp
[js]: https://github.com/DTRExp/dtrexp-js
[py]: https://github.com/DTRExp/dtrexp-py
[go]: https://github.com/DTRExp/dtrexp-go
[swift]: https://github.com/DTRExp/dtrexp-swift
[rs]: https://github.com/DTRExp/dtrexp-rs
[vectors]: https://github.com/DTRExp/dtrexp/blob/main/vectors.json
[vectors-md]: https://github.com/DTRExp/dtrexp/blob/main/VECTORS.md
[wasm]: https://github.com/DTRExp/dtrexp-wasm
