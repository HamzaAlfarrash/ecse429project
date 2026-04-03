# Static Analysis Recommendations

Use this file to convert SonarQube findings into report-ready recommendations.

## Recommended Changes

1. Reduce high-complexity methods in the Todo API modules.
2. Remove duplicated logic where the same behavior appears across request handling paths.
3. Isolate validation and error-mapping code to reduce fragility when endpoints change.
4. Simplify dense classes or modules that mix multiple responsibilities.

## Evidence References

- SonarQube dashboard:
- `artifacts/project-measures.json`:
- `artifacts/issues-search.json`:
