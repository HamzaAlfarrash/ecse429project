# ECSE 429 PROJECT

## Contributors

Hamza Alfarrash - 261017161

## Part A

[Exploratory Testing Notes](https://github.com/HamzaAlfarrash/ecse429project/blob/main/session_notes/Session1Notes.txt)

[Unit Test Suite](https://github.com/HamzaAlfarrash/ecse429project/blob/main/src/test/java/unitTest/TodoUnitTest.java)

[Unit Test Suite Video](https://drive.google.com/file/d/1wdBz43Blz9MrlH2MhtGLlfvvBjNhSoKr/view?usp=sharing)

[Report PDF File](https://github.com/HamzaAlfarrash/ecse429project/blob/main/ECSE429_Report1.pdf)

## Part B

[Gherkin Feature Files](https://github.com/HamzaAlfarrash/ecse429project/tree/main/src/test/resources/features)

[Step Definition Tests](https://github.com/HamzaAlfarrash/ecse429project/tree/main/src/test/java/storyTest)

[Step Definition Tests Video](https://drive.google.com/file/d/1jb-HI2oKMhjDkSnxZX7piRlL9aiiKzlL/view?usp=drive_link)

[Report PDF File](https://github.com/HamzaAlfarrash/ecse429project/blob/main/ECSE429_Report2.pdf)

## Part C

[Part C Overview](https://github.com/HamzaAlfarrash/ecse429project/tree/main/part_c)

[Performance Runner Source](https://github.com/HamzaAlfarrash/ecse429project/tree/main/src/perfTest/java/performance)

[Static Analysis Workflow](https://github.com/HamzaAlfarrash/ecse429project/tree/main/part_c/static-analysis)

[Performance Test Suite Video](https://drive.google.com/file/d/1uNM9JDggrev0NLAlaBb0m67iF1jSRHlT/view?usp=sharing)

[Report PDF File](https://github.com/HamzaAlfarrash/ecse429project/blob/main/ECSE429_Report3.pdf)
### Running Part C

Performance experiments:

```bash
./gradlew perfTest
```

Static analysis setup and SonarQube run:

```bash
./scripts/setup_thingifier_source.sh
./scripts/run_sonarqube_analysis.sh
```
