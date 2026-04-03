# Part C

This directory contains the non-functional testing deliverables for the `todos` portion of the ECSE 429 project.

## Structure

- `performance/`: performance runner notes, generated CSV outputs, screenshot placeholders, and video notes
- `static-analysis/`: external-source SonarQube workflow, findings placeholders, and exported analysis artifacts
- `report/`: outline for assembling the written report

## Commands

Run the performance experiments:

```bash
./gradlew perfTest
```

Run a faster smoke pass:

```bash
./gradlew perfTest -PpartcTiers=10 -PpartcIterations=1
```

Prepare the upstream Thingifier source at tag `1.5.5`:

```bash
./scripts/setup_thingifier_source.sh
```

Run the SonarQube 9.9 LTS workflow against the external checkout:

```bash
./scripts/run_sonarqube_analysis.sh
```
