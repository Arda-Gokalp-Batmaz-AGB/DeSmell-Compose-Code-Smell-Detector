# How to Run Lint on App Module

## Quick Commands

### Run Lint for Debug Build (Most Common)
```bash
# From project root
./gradlew :app:lintDebug

# Windows
gradlew.bat :app:lintDebug
```

### Run Lint for Release Build
```bash
./gradlew :app:lintRelease
```

### Run Lint for All Variants
```bash
./gradlew :app:lint
```

### Run Lint and Continue on Errors
```bash
./gradlew :app:lintDebug --continue
```

## Viewing Reports

After running lint, reports are generated in:
```
app/build/reports/lint/
├── lint-results-debug.html    (HTML report - best for viewing)
├── lint-results-debug.xml     (XML report - for CI/CD)
└── lint-results-debug.txt     (Text report - plain text)
```

### Open HTML Report

**Windows:**
```bash
start app\build\reports\lint\lint-results-debug.html
```

**Mac/Linux:**
```bash
open app/build/reports/lint/lint-results-debug.html
# or
xdg-open app/build/reports/lint/lint-results-debug.html
```

## What Gets Checked

The app module includes:
- ✅ Standard Android lint checks
- ✅ **Custom Compose code smell detector** (`:smell-detector`)
  - All 13 implemented detectors
  - CFC, SEC, SED, LIU, and more

## Troubleshooting

### Lint Not Running
- Make sure you're in the project root directory
- Ensure Gradle wrapper is executable: `chmod +x gradlew` (Linux/Mac)

### Reports Not Generated
- Check that lint task completed successfully
- Verify the build directory exists: `app/build/reports/lint/`

### Custom Detectors Not Appearing
- Verify dependency in `app/build.gradle.kts`: `lintChecks(project(":smell-detector"))`
- Make sure `:smell-detector` module is built: `./gradlew :smell-detector:build`

## Notes

- Lint runs on the `app` module only (not `smell-detector` module)
- Reports include both standard Android lint and custom Compose detectors
- HTML report is the easiest way to view results

