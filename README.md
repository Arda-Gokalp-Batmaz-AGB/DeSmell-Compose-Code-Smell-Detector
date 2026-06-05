# DeSmell: Static Detection of Presentation-Layer Code Smells in Declarative Android Architectures

[![Version](https://img.shields.io/badge/version-1.3.9-blue.svg)](https://github.com/Arda-Gokalp-Batmaz-AGB/DeSmell-Compose-Code-Smell-Detector)
[![Lint API](https://img.shields.io/badge/Lint%20API-31.5.0-green.svg)](https://developer.android.com/studio/write/lint)
[![Kotlin](https://img.shields.io/badge/Kotlin-1.9.0-purple.svg)](https://kotlinlang.org/)
[![AGP](https://img.shields.io/badge/AGP-8.6.0-orange.svg)](https://developer.android.com/studio/releases/gradle-plugin)
[![📘 Documentation](https://img.shields.io/badge/docs-GitHub%20Pages-blue)](https://arda-gokalp-batmaz-agb.github.io/DeSmell-Compose-Code-Smell-Detector/)

## Introduction

**DeSmell** is a static analysis tool that identifies architectural and maintainability code smells in modern declarative Android presentation layers built with Jetpack Compose. Unlike traditional Android code smell detectors designed for imperative XML/View architectures, DeSmell operationalizes a comprehensive taxonomy of fourteen presentation-layer smells derived via the Goal-Question-Metric (GQM) paradigm.

### Aim

The adoption of declarative UI frameworks like Jetpack Compose invalidates traditional Android quality models that rely on imperative XML and View hierarchies. While industrial tools address API correctness, a significant analytical gap remains regarding architectural degradation in compositional, state-driven interfaces. DeSmell bridges this gap by:

- **Detecting architectural degradation patterns** specific to declarative UI frameworks
- **Identifying maintainability risks** through metric-based analysis of recomposition complexity and logic accumulation
- **Providing actionable feedback** with precise locations and measurable signals in IDE and CI environments
- **Complementing existing tools** by detecting unique maintainability flaws distinct from standard linters

DeSmell integrates seamlessly with Android Lint, providing real-time feedback during development and automated quality checks in continuous integration pipelines.

> 📘 **📖 Smell Detection Catalog**: For comprehensive documentation with detailed explanations, code examples, and metrics for all code smells, visit the [**DeSmell Smell Detection Catalog**](https://arda-gokalp-batmaz-agb.github.io/DeSmell-Compose-Code-Smell-Detector/) on GitHub Pages.

---

## Table of Contents

- [Introduction](#introduction)
- [Installation](#installation)
  - [IDE Integration](#ide-integration)
  - [CI/CD Integration](#cicd-integration)
- [Thesis Results and Empirical Evaluation](#thesis-results-and-empirical-evaluation)
- [Detected Code Smells](#detected-code-smells)
- [Configuration](#configuration)
- [Usage Examples](#usage-examples)
- [Version Support](#version-support)
- [Academic References](#academic-references)
- [Contributing](#contributing)
- [License](#license)

---

## Installation

### Current Version

**Latest Version**: `1.3.9`

**Maven Coordinate**: `io.github.arda-gokalp-batmaz-agb:compose-code-smell-detector:1.3.9`

### Maven Central Integration

DeSmell is published to Maven Central. Ensure `mavenCentral()` is in your repository list (it is included by default in modern Android projects).

#### Step 1: Add Repository

In your project-level `build.gradle.kts` or `settings.gradle.kts`:

```kotlin
repositories {
    mavenCentral()
    google()
    // ... other repositories
}
```

#### Step 2: Add Dependency

In your app module's `build.gradle.kts`:

```kotlin
dependencies {
    lintChecks("io.github.arda-gokalp-batmaz-agb:compose-code-smell-detector:1.3.9")
}
```

---

## IDE Integration

### Android Studio Setup

DeSmell integrates automatically with Android Studio's built-in Lint system. Once added as a dependency, it will run during:

- **Real-time analysis**: As you type (if enabled in settings)
- **Manual inspection**: When running "Analyze > Inspect Code"
- **Build-time checks**: During Gradle builds (if configured)

#### Enable Lint Checks

In your `build.gradle.kts`:

```kotlin
android {
    lint {
        checkReleaseBuilds = true
        checkAllWarnings = true
        abortOnError = false
        textReport = true
    }
}
```

#### View Results

- **In Editor**: Issues appear as warnings/errors with red/yellow underlines
- **Problems Panel**: `View > Tool Windows > Problems`
- **Lint Report**: `Build > Analyze > Inspect Code`

#### Running Lint Manually

**From Android Studio:**
- `Build > Analyze > Inspect Code` (entire project)
- `Build > Run Lint` (current module)

**From Command Line:**
```bash
# Run lint for debug build
./gradlew :app:lintDebug

# Run lint for release build
./gradlew :app:lintRelease

# Run lint for all variants
./gradlew :app:lint
```

#### Viewing Lint Reports

Reports are generated in: `app/build/reports/lint/`

- **HTML Report**: `lint-results-debug.html` (best for viewing)
- **XML Report**: `lint-results-debug.xml` (for CI/CD parsing)
- **Text Report**: `lint-results-debug.txt` (plain text)

---

## CI/CD Integration

DeSmell can be integrated into various CI/CD platforms to enforce code quality standards automatically.

### GitHub Actions

Create `.github/workflows/lint.yml`:

```yaml
name: Lint Check

on:
  pull_request:
    branches: [ main, develop ]
  push:
    branches: [ main, develop ]

jobs:
  lint:
    runs-on: ubuntu-latest
    
    steps:
      - name: Checkout code
        uses: actions/checkout@v4
      
      - name: Set up JDK 17
        uses: actions/setup-java@v4
        with:
          distribution: 'temurin'
          java-version: '17'
          cache: 'gradle'
      
      - name: Grant execute permission for gradlew
        run: chmod +x gradlew
      
      - name: Run Lint
        run: ./gradlew :app:lintDebug --continue
      
      - name: Upload Lint Reports
        uses: actions/upload-artifact@v4
        if: always()
        with:
          name: lint-reports
          path: app/build/reports/lint/
          retention-days: 30
```

### GitLab CI

Create `.gitlab-ci.yml`:

```yaml
stages:
  - lint

lint:
  stage: lint
  image: openjdk:17-jdk
  before_script:
    - chmod +x ./gradlew
  script:
    - ./gradlew :app:lintDebug --continue
  artifacts:
    when: always
    paths:
      - app/build/reports/lint/
    reports:
      junit: app/build/reports/lint/lint-results-debug.xml
    expire_in: 1 week
  only:
    - merge_requests
    - main
    - develop
```

### Jenkins

#### Pipeline Script (Jenkinsfile)

```groovy
pipeline {
    agent any
    
    tools {
        jdk 'JDK-17'
        gradle 'Gradle-8.0'
    }
    
    stages {
        stage('Lint Check') {
            steps {
                sh './gradlew :app:lintDebug --continue'
            }
        }
        
        stage('Publish Lint Report') {
            steps {
                publishHTML([
                    reportDir: 'app/build/reports/lint',
                    reportFiles: 'lint-results-debug.html',
                    reportName: 'Lint Report',
                    keepAll: true
                ])
            }
        }
    }
    
    post {
        always {
            archiveArtifacts artifacts: 'app/build/reports/lint/**/*',
                             allowEmptyArchive: true
        }
    }
}
```

### CircleCI

Create `.circleci/config.yml`:

```yaml
version: 2.1

jobs:
  lint:
    docker:
      - image: cimg/android:2023.12
    steps:
      - checkout
      - restore_cache:
          keys:
            - gradle-dependencies-{{ checksum "build.gradle.kts" }}
      - run:
          name: Run Lint
          command: ./gradlew :app:lintDebug --continue
      - store_artifacts:
          path: app/build/reports/lint
          destination: lint-reports
      - store_test_results:
          path: app/build/reports/lint/lint-results-debug.xml

workflows:
  version: 2
  lint-workflow:
    jobs:
      - lint
```

### CI/CD Best Practices

1. **Fail on Critical Issues**: Configure `abortOnError = true` for specific detectors
2. **Baseline Files**: Use lint baseline to ignore existing issues
3. **Incremental Checks**: Only lint changed files in large projects
4. **Report Artifacts**: Always archive lint reports for review
5. **PR Comments**: Automatically comment on PRs with lint results

#### Creating a Lint Baseline

```bash
# Generate baseline (ignores existing issues)
./gradlew :app:lintDebug -Dlint.baseline=app/lint-baseline.xml
```

Then reference in `build.gradle.kts`:

```kotlin
android {
    lint {
        baseline = file("app/lint-baseline.xml")
    }
}
```

---

## Thesis Results and Empirical Evaluation

DeSmell was developed and validated as part of master's thesis research on static detection of presentation-layer code smells in declarative Android architectures. The tool was evaluated on ten open-source Jetpack Compose project snapshots to assess its effectiveness and unique coverage compared to baseline tools. Developer survey validation used a labeled five-project subset drawn from that evaluation dataset.

### Evaluation Design

The evaluation followed a scope-controlled, reproducible protocol comparing overlap and unique coverage across tools, rather than interpreting raw warning volume as quality. The study analyzed:

- **10 Open-Source Projects**: Only `@Composable` (UI) code was analyzed
- **3 Practical Baselines + DeSmell**: Android Lint (Compose checks), Slack Compose Lints, Compose Stability Analyzer, and DeSmell
- **Standardized Outputs**: Findings were normalized and compared for overlap and unique coverage

### Selected Projects

The evaluation was conducted on the following open-source projects:

| Project | Total LOC | UI LOC | # Composables | Commit |
|---------|-----------|--------|---------------|--------|
| Podcast App | 2,741 | 1,735 | 53 | 8caa993 |
| WhatsApp Clone | 4,278 | 874 | 45 | 87c7edc |
| Cars Application | 3,103 | 384 | 13 | d7f3f0a |
| TaigaMobile | 16,539 | 2,270 | 108 | f71ee71 |
| OpenCord | 17,125 | 3,431 | 70 | be63630 |
| ReadYou | 42,369 | 11,004 | 285 | 19d4973 |
| Afinity | 83,259 | 26,412 | 383 | cb11a06 |
| Thor | 13,689 | 2,793 | 51 | ae06765 |
| WhereIsMyMotivation | 13,828 | 3,731 | 152 | 0653f41 |
| MAuth | 9,587 | 3,011 | 95 | e96cbe3 |

*Reference: [SIZES OF THE SELECTED OPEN-SOURCE PROJECTS](Results/SIZES%20OF%20THE%20SELECTED%20OPEN-SOURCE%20PROJECTS.png)*

### Detection Results

#### Project × DeSmell Detection Distribution

DeSmell detected **441** total code smells across the ten projects. Although DeSmell defines 14 presentation-layer smells, **9 smell types** appeared in the analyzed snapshots under the adopted scope and counting rule; the remaining smell types were absent in this empirical run and are reported as zero in Table 5.1.

| Project | Const. In Comp. | Multi. Flow Coll. | Comp. Func. Complexity | Logic in UI | Mutable State Cond. | State Mutation | Slot Count | SEC | SED | Total |
|---------|----------------|-------------------|------------------------|-------------|---------------------|----------------|------------|-----|-----|-------|
| Podcast App | 0 | 0 | 0 | 0 | 2 | 0 | 0 | 0 | 0 | 2 |
| WhatsApp Clone | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 |
| Cars Application | 1 | 0 | 0 | 0 | 1 | 1 | 0 | 0 | 0 | 3 |
| TaigaMobile | 10 | 12 | 4 | 6 | 0 | 0 | 0 | 0 | 0 | 32 |
| OpenCord | 1 | 0 | 5 | 1 | 0 | 0 | 3 | 0 | 0 | 10 |
| ReadYou | 4 | 0 | 31 | 22 | 11 | 2 | 2 | 1 | 1 | 74 |
| Afinity | 5 | 26 | 96 | 91 | 10 | 3 | 0 | 0 | 2 | 233 |
| Thor | 0 | 2 | 11 | 9 | 0 | 0 | 0 | 0 | 0 | 22 |
| WhereIsMyMotivation | 0 | 2 | 8 | 17 | 0 | 0 | 0 | 0 | 0 | 27 |
| MAuth | 0 | 4 | 17 | 6 | 1 | 0 | 9 | 1 | 0 | 38 |
| **TOTAL** | **21** | **46** | **172** | **152** | **25** | **6** | **14** | **2** | **3** | **441** |

*Reference: [PROJECT × DESMELL DETECTION DISTRIBUTION](Results/PROJECT%20×%20DESMELL%20DETECTION%20DISTRIBUTION%20(FILE%20×%20SMELL).png)*

#### Detection Density Per Project

Detection density (detections per 1,000 lines of UI code):

| Project | Composable LOC | Detected | Detections / 1k LOC |
|---------|----------------|----------|---------------------|
| Podcast App | 1,735 | 2 | 1.15 |
| WhatsApp Clone | 874 | 0 | 0.00 |
| Cars Application | 384 | 3 | 7.81 |
| TaigaMobile | 2,270 | 32 | 14.09 |
| OpenCord | 3,431 | 10 | 2.91 |
| ReadYou | 11,004 | 74 | 6.72 |
| Afinity | 26,412 | 233 | 8.82 |
| Thor | 2,793 | 22 | 7.88 |
| WhereIsMyMotivation | 3,731 | 27 | 7.24 |
| MAuth | 3,011 | 38 | 12.62 |

*Reference: [DESMELL DETECTION DENSITY PER PROJECT](Results/DESMELL%20DETECTION%20DENSITY%20PER%20PROJECT.png)*

### Comparison with Baseline Tools

DeSmell was compared against three baseline tools to assess unique coverage:

#### Mapping DeSmell Smells to Baseline Tool Signals

| DeSmell Smell | Category | Closest Baseline Signal | Baseline Tool | Comparability |
|---------------|----------|------------------------|--------------|---------------|
| Mutable State in Boolean Conditions | Recomposition Efficiency & State Handling | Autoboxing state creation, unstable state warnings | Android Lint (Compose) | Partial |
| Declaring Constants Inside Composables | Recomposition Efficiency & State Handling | None | — | None |
| Mutable States Mutated in Compose | Recomposition Efficiency & State Handling | Generic side-effect misuse warnings | Android Lint (Compose) | Partial |
| Using rememberUpdatedState for Constants | Recomposition Efficiency & State Handling | API misuse hints | Slack Compose Lints | Partial |
| Reactive State Propagation Depth (RSPD) | Architectural Responsibility & Logic Leakage | None | — | None |
| Using Non-Snapshot-Aware Collections in State | Recomposition Efficiency & State Handling | None | — | None |
| Logic in UI Density (LIU) | Architectural Responsibility & Logic Leakage | None | — | None |
| Multiple Flow Collections per Composable | Architectural Responsibility & Logic Leakage | None | — | None |
| Composable Function Complexity (CFC) | Architectural Responsibility & Logic Leakage | None | — | None |
| Slot Count (SC) | API Design & Component Composition | Parameter order and modifier checks | Slack Compose Lints | Weak |
| Non-Savable Type in rememberSaveable | State Restoration & Lifecycle Correctness | rememberSaveable warnings | Android Lint (Compose) | Strong |
| Reusing the Same Key Across Nested Scopes | State Restoration & Lifecycle Correctness | None | — | None |
| Side-Effect Complexity (SEC) | Side-Effect Orchestration | Stability and restartable-effect signals | Compose Stability Analyzer | Partial |
| High Side-Effect Density (SED) | Side-Effect Orchestration | None | — | None |

*Reference: [MAPPING DESMELL PRESENTATION-LAYER SMELLS TO CLOSEST BASELINE TOOL SIGNALS](Results/MAPPING%20DESMELL%20PRESENTATION-LAYER%20SMELLS%20TO%20CLOSEST%20BASELINE%20TOOL%20SIGNALS.png)*

#### Key Findings

- **Unique Coverage**: 8 out of 14 DeSmell detectors have no equivalent in baseline tools (marked as "None")
- **Partial Overlap**: 4 detectors have partial comparability with baseline tools but detect different aspects
- **Weak Overlap**: 1 detector (Slot Count) has weak comparability with Slack Compose Lints
- **Strong Overlap**: Only 1 detector (Non-Savable Type in rememberSaveable) has strong comparability with Android Lint
- **Architectural Focus**: DeSmell uniquely identifies architectural degradation patterns (LIU, CFC, Multiple Flow Collections, SED, RSPD) not covered by rule-based linters

### Visual Results

For detailed visualizations and charts, refer to the [Results](Results/) folder:

- **[ALL PROPOSED PRESENTATION-LAYER CODE SMELLS](Results/ALL%20PROPOSED%20PRESENTATION-LAYER%20CODE%20SMELLS.png)**: Complete taxonomy of 14 code smells
- **[MAPPING DESMELL PRESENTATION-LAYER SMELLS TO CLOSEST BASELINE TOOL SIGNALS](Results/MAPPING%20DESMELL%20PRESENTATION-LAYER%20SMELLS%20TO%20CLOSEST%20BASELINE%20TOOL%20SIGNALS.png)**: Baseline tool applicability, comparability, and analytical gaps for all 14 DeSmell smells
- **[BASELINE TOOL OUTPUTS RAW VS. COMPOSE-FOCUSED FINDINGS](Results/BASELINE%20TOOL%20OUTPUTS%20RAW%20VS.%20COMPOSE-FOCUSED%20FINDINGS.png)**: Raw vs. Compose-focused baseline outputs across all ten projects (Android Lint, Slack Compose Lints, Compose Stability Analyzer), including per-project Compose-focused counts

### Validation Methodology

The metrics, thresholds, and detection rules implemented in DeSmell have been validated through a comprehensive developer feedback process. Thresholds and decision rules were fixed **before** survey deployment; survey responses were used to assess agreement and interpretability rather than to tune detector thresholds. This validation ensures that the tool's detections align with real-world developer perceptions of code quality issues in Jetpack Compose codebases.

#### Survey Dataset Artifacts

The labeled validation dataset and anonymized raw survey responses are published in the repository in both **CSV** (for scripts, diffing, and reproducibility) and **XLSX** (for spreadsheet viewing):

| Dataset | CSV | XLSX |
|---------|-----|------|
| Labeled validation dataset (40 composable stimuli with DeSmell CS/NS ground-truth labels) | [`Developer_Survey_Labeled_Data.csv`](docs/Developer_Survey_Labeled_Data.csv) | [`Developer_Survey_Labeled_Data.xlsx`](docs/Developer_Survey_Labeled_Data.xlsx) |
| Anonymized raw Google Form responses (9 participants × 40 maintainability questions) | [`Developer_Survey_Results.csv`](docs/Developer_Survey_Results.csv) | [`Developer_Survey_Results.xlsx`](docs/Developer_Survey_Results.xlsx) |

#### Labeled Validation Dataset

Each row in `Developer_Survey_Labeled_Data.csv` / `.xlsx` links one survey stimulus to its DeSmell classification:

| Column | Description |
|--------|-------------|
| `ID` | Stimulus identifier (1–40) |
| `Function` | Composable function name shown in the survey |
| `GroundTruth` | DeSmell label: `CS` (Code Smell) or `NS` (Not Code Smell) |
| `Class` | Binary encoding (`1` = CS, `0` = NS) |
| `Notes` | Label description |

**Summary:** 40 composable functions — **21 Code Smell (CS)**, **19 Not Code Smell (NS)** — drawn from the five-project evaluation subset (Podcast App, WhatsApp Clone, Cars Application, TaigaMobile, OpenCord).

| ID | Function | Ground Truth | ID | Function | Ground Truth |
|----|----------|--------------|----|----------|--------------|
| 1 | DashboardScreen | CS | 21 | WhatsAppVideoCallContent | NS |
| 2 | ChannelListRegularItem | NS | 22 | EmbedVideo | CS |
| 3 | ChannelsListLoaded | CS | 23 | ChannelsListLoading | NS |
| 4 | PodcastBottomBarStatelessContent | NS | 24 | UserProfileSync | CS |
| 5 | CommonTaskHeader | CS | 25 | HorizontalTabbedPager | NS |
| 6 | WhatsAppStatus | NS | 26 | WikiListScreen | CS |
| 7 | mentionsScreen | CS | 27 | Chat | NS |
| 8 | AddCarScreen | NS | 28 | GuildsListTextItem | CS |
| 9 | TransactionFinalizer | CS | 29 | EpicItemWithAction | NS |
| 10 | OCImage | NS | 30 | KanbanBoard | CS |
| 11 | customField | CS | 31 | ReactionsMenu | NS |
| 12 | CommonTaskAppBar | NS | 32 | CurrentUserContent | CS |
| 13 | PinsScreenLoaded | CS | 33 | DetailScreen | NS |
| 14 | MessageReferenced | NS | 34 | TaskFilters | CS |
| 15 | PodcastApp | NS | 35 | Editor | NS |
| 16 | ScrumScreenContent | CS | 36 | ChatLoaded | CS |
| 17 | LazyListScope.CommonTaskAssignees | NS | 37 | PodcastPlayerBody | CS |
| 18 | MessageRegular | CS | 38 | HomeScreen | NS |
| 19 | ChannelListCategoryItem | NS | 39 | MessageMenu | CS |
| 20 | CommonTaskScreen | CS | 40 | Embed | CS |

#### Developer Feedback Validation

The validation process involved:

1. **Developer Surveys**: Structured surveys were conducted with developers to evaluate whether code fragments flagged (or not flagged) by DeSmell represent actual maintainability, modifiability, readability, testability, recomposition/performance, and lifecycle/state management problems — without revealing smell terminology or tool labels during evaluation.

2. **Fixed Thresholds**: Default thresholds for metrics such as Composable Function Complexity (CFC), Side Effect Density (SED), Logic in UI Density (LIU), and Slot Count were established prior to the survey and held constant throughout validation.

3. **Pattern Validation**: Each code smell pattern was evaluated against developer judgments to confirm that detections correspond to genuine architectural and maintainability concerns rather than false positives.

#### Participant Profiles

Profiles were extracted from the anonymized responses in `Developer_Survey_Results.csv` / `.xlsx` (n = 9):

| Attribute | Category | Count |
|-----------|----------|-------|
| Android experience | 0–1 years | 4 |
| | 1–3 years | 1 |
| | 5–8 years | 2 |
| | 8+ years | 2 |
| Compose experience | 0–1 years | 4 |
| | 1–3 years | 1 |
| | 3–5 years | 3 |
| | 5+ years | 1 |
| Static analysis tools | Android Lint (Compose) only | 3 |
| | Multiple tools | 4 |
| | None / Missing | 2 |

Roles: 7 Android Developers, 2 Analysts. Participants evaluated 40 composable fragments each using a three-option scale (*Evet* / *Hayır* / *Emin değilim*).

#### Survey Results Summary

The developer survey evaluated **40** Jetpack Compose composable functions (**21** Code Smell, **19** Not Code Smell) with **9** participants, yielding **360** total judgments and **348** valid judgments after excluding *Not sure* (*Emin değilim*) responses:

**Aggregated confusion matrix** (DeSmell labels as predictions, developer judgments as perceived labels; CS = positive class):

| | Predicted CS | Predicted NS |
|---|-------------|-------------|
| **Actual CS** | 150 (TP) | 31 (FN) |
| **Actual NS** | 60 (FP) | 107 (TN) |

| Metric | Value |
|--------|-------|
| Accuracy | 73.85% |
| Precision (CS) | 71.43% |
| Recall (CS) | 82.87% |
| Specificity (NS) | 64.07% |
| F1-score (CS) | 76.73% |
| Balanced Accuracy | 73.47% |
| MCC | 0.479 |
| Fleiss' κ (inter-rater) | 0.269 |
| Function-level agreement (majority vote) | 89.74% (35/39) |

Raw per-participant responses for all 40 stimuli are available in [`docs/Developer_Survey_Results.csv`](docs/Developer_Survey_Results.csv) and [`docs/Developer_Survey_Results.xlsx`](docs/Developer_Survey_Results.xlsx).

#### Survey Links

Developers can participate in the ongoing validation process by completing the following surveys:

- **English Survey**: [Jetpack Compose UI Codes Evaluation Survey](https://forms.gle/mzmDafk6mxFbqvqQ8)
- **Turkish Survey**: [Jetpack Compose UI Kodları Değerlendirme Anketi](https://forms.gle/Eb3Duh2SHMFaPsB37)

These surveys evaluate Jetpack Compose UI code snippets to assess whether certain code patterns cause problems in terms of maintainability, modifiability, readability, testability, recomposition/performance, and lifecycle/state management. All responses are used anonymously for academic research purposes.

### Empirical Validation

The empirical results confirm that:

1. **DeSmell detects unique maintainability flaws** distinct from standard tools like Slack Compose Lints or Android Lint
2. **Metric-based analysis is necessary** for modern declarative architectures, as rule-based linters miss architectural degradation patterns
3. **Detection density varies** across projects (0.00 to 14.09 detections per 1k LOC), indicating project-specific quality characteristics
4. **Architectural smells are prevalent** in real-world projects, with Composable Function Complexity (172 detections), Logic in UI Density (152), and Multiple Flow Collections (46) being most common
5. **Developer-validated thresholds** show moderate alignment with practitioner perception (73.85% accuracy, MCC 0.479) while surfacing reproducible judgments for perception-sensitive cases

### Academic Reference

For detailed methodology, theoretical foundations, and complete evaluation results, see:

- **Results Folder**: [Results/](Results/) - Contains all evaluation tables, charts, and visualizations
- **Survey Labeled Dataset**: [CSV](docs/Developer_Survey_Labeled_Data.csv) · [XLSX](docs/Developer_Survey_Labeled_Data.xlsx) — 40 composable stimuli with DeSmell CS/NS ground-truth labels
- **Survey Raw Responses**: [CSV](docs/Developer_Survey_Results.csv) · [XLSX](docs/Developer_Survey_Results.xlsx) — Anonymized developer survey responses (9 participants)
- **Research Repository**: [GitHub Repository](https://github.com/Arda-Gokalp-Batmaz-AGB/DeSmell-Compose-Code-Smell-Detector)

---

## Detected Code Smells

DeSmell implements 14 specialized detectors for identifying presentation-layer code smells in Jetpack Compose applications:

> 📖 **Detailed Documentation**: For comprehensive information about each smell, including detection rules, metrics, formulas, thresholds, and practical code examples, see the [**DeSmell Smell Detection Catalog**](https://arda-gokalp-batmaz-agb.github.io/DeSmell-Compose-Code-Smell-Detector/) on GitHub Pages. The catalog provides developer-friendly explanations, good/bad code examples, and actionable guidance for fixing each detected smell.

### Complete Detector Reference

| Detector ID | Category | Severity | Description |
|------------|----------|----------|-------------|
| `ConstantsInComposable` | Recomposition Efficiency & State Handling | Warning | Immutable constants declared inside composables |
| `SlotCountInComposable` | API Design & Component Composition | Warning | Excessive slot parameters (>6) |
| `ReusedKeyInNestedScope` | State Restoration & Lifecycle Correctness | Warning | Key reuse in nested scopes |
| `MutableStateMutationInComposable` | Recomposition Efficiency & State Handling | Warning | State mutations during composition |
| `LogicInUiIssue` | Architectural Responsibility & Logic Leakage | Warning | Excessive control-flow logic in UI |
| `MutableStateInCondition` | Recomposition Efficiency & State Handling | Warning | Mutable state in conditional expressions |
| `RememberUpdatedStateWithConstant` | Recomposition Efficiency & State Handling | Warning | rememberUpdatedState with constants |
| `NonSavableRememberSaveable` | State Restoration & Lifecycle Correctness | Warning | Non-savable types in rememberSaveable |
| `MultipleFlowCollectionsPerComposable` | Architectural Responsibility & Logic Leakage | Warning | Multiple Flow collections per composable |
| `SideEffectComplexityIssue` | Side-Effect Orchestration | Warning | Complex side effects |
| `ComposableFunctionComplexityIssue` | Architectural Responsibility & Logic Leakage | Warning | High structural/logical complexity (CFC) |
| `HighSideEffectDensityIssue` | Side-Effect Orchestration | Warning | High side effect density (SED) |
| `ReactiveStatePassThrough` | Recomposition Efficiency & State Handling | Warning | Reactive state passed through ≥2 layers without use |
| `NonSnapshotAwareCollectionInState` | Recomposition Efficiency & State Handling | Warning | In-place mutation of non-snapshot collection stored in state |

### Metrics Explained

All metrics and their thresholds have been validated through developer feedback surveys to ensure they align with real-world code quality concerns. The default thresholds represent a balance between sensitivity and practical utility, calibrated based on developer evaluations of code patterns.

#### Logic in UI Density (LIU)
Measures control-flow density in composables:
- **Formula**: `LIU = (CF_render + 0.5 × CF_behavior) / statements`
- **Threshold**: 2-6 control flow constructs per statement count (validated via developer surveys)
- **Purpose**: Identifies when UI layer accumulates procedural orchestration logic

#### Composable Function Complexity (CFC)
Measures structural and logical complexity by combining:
- Control-flow constructs (if, when, for, while)
- Nesting depth
- Side-effect usage (SEC)
- ViewModel access
- Parameter count
- Statement count

**Threshold**: Default 25 (configurable, validated via developer feedback)

#### Side-Effect Complexity (SEC)
Measures complexity within side-effect blocks:
- Branches, loops, nesting depth
- Nested launched scopes/effects
- Statement count

**Threshold**: Default 10 (configurable, validated via developer feedback)

#### Side Effect Density (SED)
Ratio of side effects to UI nodes in a composable:
- **Formula**: `SED = sideEffectCount / uiNodeCount`
- **Threshold**: Default 0.3 (30%, configurable, validated via developer feedback)
- **Purpose**: Identifies effect-heavy composables that take on controller responsibilities

---

## Configuration

### Threshold Configuration

Many detectors support configurable thresholds. Configure them in `lint.xml`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<lint>
    <!-- Composable Function Complexity -->
    <issue id="ComposableFunctionComplexity">
        <option name="cfcThreshold" value="25" />
    </issue>
    
    <!-- Side Effect Density -->
    <issue id="HighSideEffectDensity">
        <option name="sedThreshold" value="0.3" />
    </issue>
    
    <!-- Slot Count -->
    <issue id="SlotCountInComposable">
        <option name="maxSlots" value="6" />
    </issue>
</lint>
```

Place `lint.xml` in your app module root: `app/lint.xml`

### Disable Specific Checks

**In `build.gradle.kts`:**
```kotlin
android {
    lint {
        disable += listOf(
            "ConstantsInComposable",
            "SlotCountInComposable"
        )
    }
}
```

**In `lint.xml`:**
```xml
<lint>
    <issue id="ConstantsInComposable" severity="ignore" />
</lint>
```

### Change Severity Levels

```kotlin
android {
    lint {
        error += listOf("ComposableFunctionComplexity")
        warning += listOf("ConstantsInComposable")
        informational += listOf("SlotCountInComposable")
    }
}
```

---

## Usage Examples

### Example 1: Constants in Composable

**❌ Bad:**
```kotlin
@Composable
fun MyScreen() {
    val items = listOf("Item 1", "Item 2", "Item 3")  // ⚠️ Detected!
    LazyColumn {
        items(items) { item ->
            Text(item)
        }
    }
}
```

**✅ Good:**
```kotlin
private val DEFAULT_ITEMS = listOf("Item 1", "Item 2", "Item 3")

@Composable
fun MyScreen() {
    LazyColumn {
        items(DEFAULT_ITEMS) { item ->
            Text(item)
        }
    }
}
```

### Example 2: High Complexity

**❌ Bad:**
```kotlin
@Composable
fun ComplexScreen(viewModel: MyViewModel) {
    val state = viewModel.state.collectAsState()
    val data = viewModel.data.collectAsState()
    
    if (state.value.isLoading) {
        // ... 200+ lines of nested logic
    } else if (state.value.hasError) {
        // ... more nested logic
    }
    // ⚠️ ComposableFunctionComplexity detected!
}
```

**✅ Good:**
```kotlin
@Composable
fun ComplexScreen(viewModel: MyViewModel) {
    val state = viewModel.state.collectAsState()
    
    when {
        state.value.isLoading -> LoadingScreen()
        state.value.hasError -> ErrorScreen(state.value.error)
        else -> ContentScreen(viewModel)
    }
}

@Composable
private fun LoadingScreen() { /* ... */ }

@Composable
private fun ErrorScreen(error: String) { /* ... */ }

@Composable
private fun ContentScreen(viewModel: MyViewModel) { /* ... */ }
```

### Example 3: Multiple Flow Collections

**❌ Bad:**
```kotlin
@Composable
fun MyScreen(viewModel: MyViewModel) {
    val users = viewModel.users.collectAsState()      // ⚠️ Detected!
    val posts = viewModel.posts.collectAsState()       // ⚠️ Detected!
    val comments = viewModel.comments.collectAsState() // ⚠️ Detected!
    
    // ...
}
```

**✅ Good:**
```kotlin
data class ScreenState(
    val users: List<User>,
    val posts: List<Post>,
    val comments: List<Comment>
)

@Composable
fun MyScreen(viewModel: MyViewModel) {
    val state = viewModel.screenState.collectAsState()  // Single Flow
    
    // ...
}
```

---

## Version Support

### Supported Versions

| Component | Minimum Version | Recommended Version | Tested Versions |
|-----------|----------------|---------------------|-----------------|
| **Android Gradle Plugin (AGP)** | 7.0.0 | 8.6.0 | 7.0.0 - 8.6.0 |
| **Lint API** | 30.0.0 | 31.5.0 | 30.0.0 - 31.5.0 |
| **Kotlin** | 1.7.0 | 1.9.0 | 1.7.0 - 1.9.0 |
| **Jetpack Compose** | 1.0.0 | 1.5.1+ | 1.0.0 - 1.5.1+ |
| **Java** | 11 | 17 | 11, 17 |
| **Gradle** | 7.0 | 8.0+ | 7.0 - 8.0+ |

### Android SDK Support

- **Minimum SDK**: API 21 (Android 5.0)
- **Target SDK**: API 34+ (Android 14+)
- **Compile SDK**: API 34+ (Android 14+)

### Compatibility Notes

- The detector uses Lint API version 31.5.0, which is compatible with AGP 7.0+
- Java 17 bytecode is used for optimal compatibility with modern Android Studio versions
- The detector is tested against Compose BOM versions from 2023.01.01 to 2024.04.01

---

## Academic References

### Research Context

DeSmell was developed as part of master's thesis research on identifying code smells and defects in the presentation layer of Android applications using Jetpack Compose. The research addresses the gap in static analysis tools for declarative UI frameworks.

### Thesis Information

- **Title**: Static Detection of Presentation-Layer Code Smells in Declarative Android Architectures
- **Advisor**: Assoc. Prof. Dr. Feza Buzluca
- **Institution**: Istanbul Technical University, Department of Computer Engineering
- **Author**: Arda Gökalp Batmaz

### Related Work and References

This detector is built upon and inspired by the following foundational work:

1. **Android Lint Framework**
   - Official documentation: [Android Lint](https://developer.android.com/studio/write/lint)
   - Provides the static analysis infrastructure and API for custom lint checks
   - Enables integration with Android Studio and CI/CD pipelines

2. **Slack Compose Lint Rules**
   - Repository: [slackhq/compose-lints](https://github.com/slackhq/compose-lints)
   - Provides utilities and patterns for writing Compose-specific lint checks
   - Used as a reference for UAST traversal and Compose-specific analysis patterns

3. **Compose Compiler Stability Analyzer**
   - Part of the Jetpack Compose compiler plugin
   - Analyzes stability of composable functions and their parameters
   - Provides insights into recomposition behavior and performance optimization
   - Documentation: [Compose Compiler](https://developer.android.com/jetpack/androidx/releases/compose-compiler)

### Evaluated Projects

The following open-source projects were used in the empirical evaluation:

1. **Podcast App** - [fabirt/podcast-app](https://github.com/fabirt/podcast-app) (Commit: 8caa993)
2. **WhatsApp Clone** - [GetStream/whatsApp-clone-compose](https://github.com/GetStream/whatsApp-clone-compose) (Commit: 87c7edc)
3. **Cars Application** - [rancicdevelopment/Cars-Application-2024](https://github.com/rancicdevelopment/Cars-Application-2024) (Commit: d7f3f0a)
4. **TaigaMobile** - [EugeneTheDev/TaigaMobile](https://github.com/EugeneTheDev/TaigaMobile) (Commit: f71ee71)
5. **OpenCord** - [MateriiApps/OpenCord](https://github.com/MateriiApps/OpenCord) (Commit: be63630)
6. **ReadYou** - [ReadYouApp/ReadYou](https://github.com/ReadYouApp/ReadYou) (Commit: 19d4973)
7. **Afinity** - [MakD/AFinity](https://github.com/MakD/AFinity) (Commit: cb11a06)
8. **Thor** - [trinadhthatakula/Thor](https://github.com/trinadhthatakula/Thor) (Commit: ae06765)
9. **WhereIsMyMotivation** - [afteracademy/wimm-android-app](https://github.com/afteracademy/wimm-android-app) (Commit: 0653f41)
10. **MAuth** - [X1nto/Mauth](https://github.com/X1nto/Mauth) (Commit: e96cbe3)

### Citation

If you use DeSmell in academic work, please cite:

```
Gökalp Batmaz, A. (2026). DeSmell: Static Detection of Presentation-Layer Code Smells 
in Declarative Android Architectures. Master's Thesis, Istanbul Technical University.
GitHub Repository: https://github.com/Arda-Gokalp-Batmaz-AGB/DeSmell-Compose-Code-Smell-Detector
```

### Key Research Papers

1. Carvalho, S. G., et al. (2019). "An empirical catalog of code smells for the presentation layer of Android apps." *Empirical Software Engineering*, 24(6), 3546-3586.

2. Palomba, F., et al. (2017). "Lightweight detection of Android-specific code smells: The aDoctor project." *2017 IEEE 24th International Conference on Software Analysis, Evolution and Reengineering (SANER)*, 487-491.

3. Hecht, G., Moha, N., & Rouvoy, R. (2016). "An empirical study of the performance impacts of Android code smells." *HAL (Le Centre pour la Communication Scientifique Directe)*, 59-69.

4. Chouchane, M., Soui, M., & Ghedira, K. (2021). "The impact of the code smells of the presentation layer on the diffuseness of aesthetic defects of Android apps." *Automated Software Engineering*, 28(2).

5. Wu, Z., Chen, X., & Lee, S. U.-J. (2023). "A systematic literature review on Android-specific smells." *Journal of Systems and Software*, 201, 111677.

---

## Contributing

Contributions are welcome! Please follow these steps:

1. Fork the repository
2. Create a feature branch (`git checkout -b feature/amazing-detector`)
3. Add tests for new detectors
4. Ensure all tests pass (`./gradlew test`)
5. Commit your changes (`git commit -m 'Add amazing detector'`)
6. Push to the branch (`git push origin feature/amazing-detector`)
7. Open a Pull Request

### Development Setup

1. Clone the repository
2. Open in Android Studio
3. Sync Gradle
4. Run tests: `./gradlew :smell-detector:test`

### Adding New Detectors

1. Create detector class in `smell-detector/src/main/java/com/arda/smell_detector/rules/`
2. Register in `SmellIssueRegistry.kt`
3. Add unit tests in `smell-detector/src/test/`
4. Update documentation

---

## License

This project is licensed under the MIT License - see the [LICENSE](LICENSE) file for details.

---

## Support

- **Issues**: [GitHub Issues](https://github.com/Arda-Gokalp-Batmaz-AGB/DeSmell-Compose-Code-Smell-Detector/issues)
- **Discussions**: [GitHub Discussions](https://github.com/Arda-Gokalp-Batmaz-AGB/DeSmell-Compose-Code-Smell-Detector/discussions)
- **Contact**: See vendor information in `SmellIssueRegistry.kt`

---

## Acknowledgments

This project builds upon and extends the work of several foundational projects:

- **Android Lint Framework**: Built on the [Android Lint Framework](https://developer.android.com/studio/write/lint) for static code analysis infrastructure
- **Slack Compose Lint Rules**: Inspired by and uses utilities from [Slack's Compose Lint Rules](https://github.com/slackhq/compose-lints) for Compose-specific analysis patterns
- **Compose Compiler Stability Analyzer**: References stability analysis concepts from the Jetpack Compose compiler plugin
- **Academic Research**: Developed as part of master's thesis research on code quality in declarative UI frameworks

---

**Last Updated**: 2026  
**Version**: 1.3.9  
**Maintainer**: Arda Gökalp Batmaz
