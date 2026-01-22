# DeSmell: Static Detection of Presentation-Layer Code Smells in Declarative Android Architectures

[![Version](https://img.shields.io/badge/version-1.3.5-blue.svg)](https://github.com/Arda-Gokalp-Batmaz-AGB/Kotlin-Code-Smell-Detector)
[![Lint API](https://img.shields.io/badge/Lint%20API-31.5.0-green.svg)](https://developer.android.com/studio/write/lint)
[![Kotlin](https://img.shields.io/badge/Kotlin-1.9.0-purple.svg)](https://kotlinlang.org/)
[![AGP](https://img.shields.io/badge/AGP-8.6.0-orange.svg)](https://developer.android.com/studio/releases/gradle-plugin)
[![📘 Documentation](https://img.shields.io/badge/docs-GitHub%20Pages-blue)](https://arda-gokalp-batmaz-agb.github.io/Kotlin-Code-Smell-Detector/)

## Introduction

**DeSmell** is a static analysis tool that identifies architectural and maintainability code smells in modern declarative Android presentation layers built with Jetpack Compose. Unlike traditional Android code smell detectors designed for imperative XML/View architectures, DeSmell operationalizes a comprehensive taxonomy of twelve presentation-layer smells derived via the Goal-Question-Metric (GQM) paradigm.

### Aim

The adoption of declarative UI frameworks like Jetpack Compose invalidates traditional Android quality models that rely on imperative XML and View hierarchies. While industrial tools address API correctness, a significant analytical gap remains regarding architectural degradation in compositional, state-driven interfaces. DeSmell bridges this gap by:

- **Detecting architectural degradation patterns** specific to declarative UI frameworks
- **Identifying maintainability risks** through metric-based analysis of recomposition complexity and logic accumulation
- **Providing actionable feedback** with precise locations and measurable signals in IDE and CI environments
- **Complementing existing tools** by detecting unique maintainability flaws distinct from standard linters

DeSmell integrates seamlessly with Android Lint, providing real-time feedback during development and automated quality checks in continuous integration pipelines.

> 📘 **📖 Smell Detection Catalog**: For comprehensive documentation with detailed explanations, code examples, and metrics for all 12 code smells, visit the [**DeSmell Smell Detection Catalog**](https://arda-gokalp-batmaz-agb.github.io/Kotlin-Code-Smell-Detector/) on GitHub Pages.

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

**Latest Version**: `1.3.5`

### Available Repositories

DeSmell is available from multiple repositories:

- **JitPack** (Recommended for quick setup) - `com.github.Arda-Gokalp-Batmaz-AGB:Kotlin-Code-Smell-Detector:1.3.5`
- **Maven Central** - `com.arda:compose-code-smell-detector:1.3.5`
- **GitHub Packages** - `com.arda:compose-code-smell-detector:1.3.5`

### JitPack Integration (Easiest)

JitPack automatically builds from GitHub releases. No additional setup needed!

#### Step 1: Add Repository

In your project-level `build.gradle.kts` or `settings.gradle.kts`:

```kotlin
repositories {
    maven { url = uri("https://jitpack.io") }
    mavenCentral()
    google()
    // ... other repositories
}
```

#### Step 2: Add Dependency

In your app module's `build.gradle.kts`:

```kotlin
dependencies {
    lintChecks("com.github.Arda-Gokalp-Batmaz-AGB:Kotlin-Code-Smell-Detector:1.3.5")
}
```

**Note**: Replace `1.3.5` with the latest release tag or commit hash (e.g., `1.3.5`, `main-SNAPSHOT`, or `abc123def`).

### Maven Central Integration

DeSmell is available via Maven Central. Add the following dependency to your project:

#### Step 1: Add Repository (if not already present)

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
    lintChecks("com.arda:compose-code-smell-detector:1.3.5")
}
```

#### Step 3: Sync Gradle

- Click "Sync Now" when prompted in Android Studio
- Or use: `File > Sync Project with Gradle Files`

The detector will be available immediately after sync.

### Local Module Integration (Alternative)

If you prefer to include the module directly in your project:

1. **Clone or include the module** in your project:

```kotlin
// settings.gradle.kts
include(":smell-detector")
```

2. **Add dependency** in your app module's `build.gradle.kts`:

```kotlin
dependencies {
    lintChecks(project(":smell-detector"))
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

DeSmell was developed and validated as part of master's thesis research on static detection of presentation-layer code smells in declarative Android architectures. The tool was evaluated on five open-source Jetpack Compose projects to assess its effectiveness and unique coverage compared to baseline tools.

### Evaluation Design

The evaluation followed a scope-controlled, reproducible protocol comparing overlap and unique coverage across tools, rather than interpreting raw warning volume as quality. The study analyzed:

- **5 Open-Source Projects**: Only `@Composable` (UI) code was analyzed
- **4 Baseline Tools**: Android Lint, Slack Compose Lints, Compose Stability Analyzer, and DeSmell
- **Standardized Outputs**: Findings were normalized and compared for overlap and unique coverage

### Selected Projects

The evaluation was conducted on the following open-source projects:

| Project | Total LOC | UI LOC | # Composables | Commit |
|---------|-----------|--------|---------------|--------|
| Podcast App | 2,741 | 1,735 | 53 | 8caa993 |
| WhatsApp Clone | 4,278 | 874 | 45 | 87c7edc |
| Cars Application | 3,103 | 384 | 13 | d7f3f0a |
| Taiga Mobile | 16,539 | 2,270 | 108 | f71ee71 |
| OpenCord | 17,125 | 3,431 | 70 | be63630 |

*Reference: [SIZES OF THE SELECTED OPEN-SOURCE PROJECTS](Results/SIZES%20OF%20THE%20SELECTED%20OPEN-SOURCE%20PROJECTS.png)*

### Detection Results

#### Project × DeSmell Detection Distribution

DeSmell detected 41 total code smells across the five projects:

| Project | Const. In Comp. | Multi. Flow Coll. | Comp. Func. Complexity | Logic in UI | Mutable State Cond. | State Mutation | Slot Count | Total |
|---------|----------------|-------------------|------------------------|-------------|---------------------|----------------|------------|-------|
| Podcast App | 0 | 0 | 0 | 0 | 2 | 0 | 0 | 2 |
| WhatsApp Clone | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 |
| Cars Application | 1 | 0 | 0 | 0 | 1 | 1 | 0 | 3 |
| Taiga Mobile | 10 | 12 | 4 | 6 | 0 | 0 | 0 | 26 |
| OpenCord | 1 | 0 | 5 | 1 | 0 | 0 | 3 | 10 |
| **TOTAL** | **12** | **14** | **9** | **8** | **3** | **2** | **3** | **41** |

*Reference: [PROJECT × DESMELL DETECTION DISTRIBUTION](Results/PROJECT%20×%20DESMELL%20DETECTION%20DISTRIBUTION%20(FILE%20×%20SMELL).png)*

#### Detection Density Per Project

Detection density (detections per 1,000 lines of UI code):

| Project | Composable LOC | Detected | Detections / 1k LOC |
|---------|----------------|----------|---------------------|
| Podcast App | 1,735 | 2 | 1.15 |
| WhatsApp Clone | 874 | 0 | 0.00 |
| Cars Application | 384 | 3 | 7.81 |
| Taiga Mobile | 2,270 | 26 | 11.45 |
| OpenCord | 3,431 | 10 | 2.91 |

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

- **Unique Coverage**: 7 out of 12 DeSmell detectors have no equivalent in baseline tools (marked as "None")
- **Partial Overlap**: 4 detectors have partial comparability with baseline tools but detect different aspects
- **Strong Overlap**: Only 1 detector (Non-Savable Type in rememberSaveable) has strong comparability with Android Lint
- **Architectural Focus**: DeSmell uniquely identifies architectural degradation patterns (LIU, CFC, Multiple Flow Collections, SED) not covered by rule-based linters

### Visual Results

For detailed visualizations and charts, refer to the [Results](Results/) folder:

- **[ALL PROPOSED PRESENTATION-LAYER CODE SMELLS](Results/ALL%20PROPOSED%20PRESENTATION-LAYER%20CODE%20SMELLS.png)**: Complete taxonomy of 12 code smells
- **[TOOL FEASIBILITY MATRIX](Results/TOOL%20FEASIBILITY%20MATRIX%20(EXECUTION%20AND%20COMPOSABLE-SCOPE%20COVERAGE).png)**: Tool applicability and execution coverage
- **[BASELINE TOOL OUTPUTS RAW VS. COMPOSE-FOCUSED FINDINGS](Results/BASELINE%20TOOL%20OUTPUTS%20RAW%20VS.%20COMPOSE-FOCUSED%20FINDINGS.png)**: Comparison of raw vs. filtered outputs
- **[COMPOSE-FOCUSED BASELINE FINDINGS PER PROJECT](Results/COMPOSE-FOCUSED%20BASELINE%20FINDINGS%20PER%20PROJECT.png)**: Baseline tool findings across projects
- **[TOP COMPOSE-FOCUSED BASELINE ISSUES PER PROJECT FOR SLACK COMPOSE LINTS](Results/TOP%20COMPOSE-FOCUSED%20BASELINE%20ISSUES%20PER%20PROJECT%20FOR%20SLACK%20COMPOSE%20LINTS.png)**: Most common issues detected by Slack Compose Lints

### Empirical Validation

The empirical results confirm that:

1. **DeSmell detects unique maintainability flaws** distinct from standard tools like Slack Compose Lints or Android Lint
2. **Metric-based analysis is necessary** for modern declarative architectures, as rule-based linters miss architectural degradation patterns
3. **Detection density varies** across projects (0.00 to 11.45 detections per 1k LOC), indicating project-specific quality characteristics
4. **Architectural smells are prevalent** in real-world projects, with Multiple Flow Collections (14 detections) and Constants in Composables (12 detections) being most common

### Academic Reference

For detailed methodology, theoretical foundations, and complete evaluation results, see:

- **Results Folder**: [Results/](Results/) - Contains all evaluation tables, charts, and visualizations
- **Research Repository**: [GitHub Repository](https://github.com/Arda-Gokalp-Batmaz-AGB/Kotlin-Code-Smell-Detector)

---

## Detected Code Smells

DeSmell implements 12 specialized detectors for identifying presentation-layer code smells in Jetpack Compose applications:

> 📖 **Detailed Documentation**: For comprehensive information about each smell, including detection rules, metrics, formulas, thresholds, and practical code examples, see the [**DeSmell Smell Detection Catalog**](docs/DeSmell_Smell_Catalog.html). The catalog provides developer-friendly explanations, good/bad code examples, and actionable guidance for fixing each detected smell.

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

### Metrics Explained

#### Logic in UI Density (LIU)
Measures control-flow density in composables:
- **Formula**: `LIU = (CF_render + 0.5 × CF_behavior) / statements`
- **Threshold**: 2-6 control flow constructs per statement count
- **Purpose**: Identifies when UI layer accumulates procedural orchestration logic

#### Composable Function Complexity (CFC)
Measures structural and logical complexity by combining:
- Control-flow constructs (if, when, for, while)
- Nesting depth
- Side-effect usage (SEC)
- ViewModel access
- Parameter count
- Statement count

**Threshold**: Default 25 (configurable)

#### Side-Effect Complexity (SEC)
Measures complexity within side-effect blocks:
- Branches, loops, nesting depth
- Nested launched scopes/effects
- Statement count

**Threshold**: Default 10 (configurable)

#### Side Effect Density (SED)
Ratio of side effects to UI nodes in a composable:
- **Formula**: `SED = sideEffectCount / uiNodeCount`
- **Threshold**: Default 0.3 (30%, configurable)
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
4. **Taiga Mobile** - [EugeneTheDev/TaigaMobile](https://github.com/EugeneTheDev/TaigaMobile) (Commit: f71ee71)
5. **OpenCord** - [MateriiApps/OpenCord](https://github.com/MateriiApps/OpenCord) (Commit: be63630)

### Citation

If you use DeSmell in academic work, please cite:

```
Gökalp Batmaz, A. (2024). DeSmell: Static Detection of Presentation-Layer Code Smells 
in Declarative Android Architectures. Master's Thesis, Istanbul Technical University.
GitHub Repository: https://github.com/Arda-Gokalp-Batmaz-AGB/Kotlin-Code-Smell-Detector
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

- **Issues**: [GitHub Issues](https://github.com/Arda-Gokalp-Batmaz-AGB/Kotlin-Code-Smell-Detector/issues)
- **Discussions**: [GitHub Discussions](https://github.com/Arda-Gokalp-Batmaz-AGB/Kotlin-Code-Smell-Detector/discussions)
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
**Version**: 1.3.5  
**Maintainer**: Arda Gökalp Batmaz
