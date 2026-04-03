# Static Analysis Workflow

Part C requires analysis of the Java source for the Todo API itself, not only this test repository. This workflow targets the upstream `eviltester/thingifier` source at tag `1.5.5`.

## Workflow

1. Fetch the external source checkout:

```bash
./scripts/setup_thingifier_source.sh
```

2. Run SonarQube `9.9 LTS` via Docker and scan the external checkout:

```bash
./scripts/run_sonarqube_analysis.sh
```

## What The Script Does

- clones or validates `thingifier` at tag `1.5.5`
- starts `sonarqube:9.9-community`
- compiles the Java source with Maven in Docker
- runs `sonar-scanner` in Docker
- exports issue and metric JSON into `part_c/static-analysis/artifacts/`

## Report Focus

Use the exported findings to support recommendations around:

- complexity and statement count
- technical debt and code smells
- rigidity and fragility risks
- needless repetition
- maintainability concerns for future enhancement or bug-fix work
