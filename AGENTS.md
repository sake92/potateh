# Agent instructions

## Scala conventions

- Avoid `return`; use expressions or `scala.util.boundary` and `boundary.break` for early exits.
- Use named tuples for tuple data with meaningful fields, and access fields by name.
- Follow `.scalafmt.conf` for formatting and syntax rewrites.

## Validation

| Check | Command |
|---|---|
| Formatting | `scalafmt --test` |
| Compile JVM and Native | `deder exec -t compile` |
| Test JVM and Native | `deder exec -t test` |

## References

- Setup and public API: `README.md`.
- Implementation boundaries: `ARCHITECTURE.md`.
- Protocol coverage: `COMPATIBILITY.md`.
- External checks and fixtures: `conformance/README.md`.
