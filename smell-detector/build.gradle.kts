import java.util.Properties

plugins {
    id("java-library")
    id("maven-publish")
    alias(libs.plugins.jetbrains.kotlin.jvm)
}

java {
    // Ensure proper bytecode for use in Android Studio
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    val lintVersion = "31.5.0"

    // Lint API for writing custom lint checks (DO NOT package)
    compileOnly("com.android.tools.lint:lint-api:$lintVersion")
    compileOnly("com.android.tools.lint:lint-checks:$lintVersion")

    // Lint API needed ALSO in tests ‼️
    testImplementation("com.android.tools.lint:lint-api:$lintVersion")
    testImplementation("com.android.tools.lint:lint-checks:$lintVersion")

    // Lint test framework
    testImplementation("com.android.tools.lint:lint-tests:$lintVersion")
    implementation(libs.junit)
    testImplementation(libs.junit)
    // Needed for Kotlin test helpers
    testImplementation(kotlin("test"))
}

//
// -------- Read local.properties for credentials --------
//
val props = Properties()
file("$rootDir/local.properties").takeIf { it.exists() }?.inputStream()?.use { props.load(it) }

val gprUser: String? = props.getProperty("gpr.user") ?: System.getenv("GPR_USER")
val gprToken: String? = props.getProperty("gpr.token") ?: System.getenv("GPR_TOKEN")

//
// -------- Publishing to GitHub Packages --------
//
publishing {
    publications {
        create<MavenPublication>("release") {
            groupId = "com.arda"
            artifactId = "compose-code-smell-detector"
            version = "1.3.5"

            from(components["java"])
        }
    }

    repositories {
        maven {
            name = "GitHubPackages"
            url = uri("https://maven.pkg.github.com/Arda-Gokalp-Batmaz-AGB/Kotlin-Code-Smell-Detector")

            credentials {
                // Prefer local.properties → fallback to env vars
                username = gprUser
                password = gprToken
            }
        }
    }
}

//
// -------- Correct Lint JAR Packaging --------
//
tasks.jar {
    from(sourceSets.main.get().output)

    // DO NOT package test classes
    exclude("**/test/**")
    exclude("**/*Test.class")
    exclude("**/*DetectorTest.class")

    manifest {
        attributes["Lint-Registry-v2"] = "com.arda.smell_detector.SmellIssueRegistry"
    }
}
