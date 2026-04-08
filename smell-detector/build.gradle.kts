import java.util.Properties
import org.gradle.authentication.http.BasicAuthentication

plugins {
    id("java-library")
    id("maven-publish")
    id("signing")
    alias(libs.plugins.jetbrains.kotlin.jvm)
}

java {
    // Ensure proper bytecode for use in Android Studio
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
    withSourcesJar()
    withJavadocJar()
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
// -------- Read properties for credentials --------
//
val props = Properties()
file("$rootDir/local.properties").takeIf { it.exists() }?.reader(Charsets.UTF_8)?.use { props.load(it) }

fun firstNonBlank(vararg candidates: String?): String? =
    candidates.asSequence().mapNotNull { it?.trim()?.takeIf { s -> s.isNotEmpty() } }.firstOrNull()

/** Gradle signing expects an 8-hex-char short key id (e.g. 00B5050F). Use 8, 16 (gpg long id), or 40 (fingerprint) hex chars. */
fun normalizePgpKeyIdForGradle(raw: String?): String? {
    val s = raw?.trim()?.removePrefix("0x")?.removePrefix("0X")?.trim() ?: return null
    if (!s.matches(Regex("[0-9a-fA-F]+"))) return null
    return when (s.length) {
        8 -> s.uppercase()
        16 -> s.takeLast(8).uppercase()
        40 -> s.takeLast(8).uppercase()
        else -> null
    }
}

// GitHub Packages: Basic auth = GitHub login (PAT owner) + PAT. Order: local.properties → Gradle props → env → GHA defaults.
val gprUser: String? = firstNonBlank(
    props.getProperty("gpr.user"),
    findProperty("gpr.user") as String?,
    System.getenv("GPR_USER"),
    System.getenv("GITHUB_ACTOR"),
)
val gprToken: String? = firstNonBlank(
    props.getProperty("gpr.token"),
    findProperty("gpr.token") as String?,
    System.getenv("GPR_TOKEN"),
    System.getenv("GITHUB_TOKEN"),
)

// Maven Central (Sonatype OSSRH) — token user + token password from https://central.sonatype.com/ or legacy OSSRH
val sonatypeUsername: String? = firstNonBlank(
    props.getProperty("sonatype.username"),
    findProperty("sonatype.username") as String?,
    System.getenv("SONATYPE_USERNAME"),
)
val sonatypePassword: String? = firstNonBlank(
    props.getProperty("sonatype.password"),
    findProperty("sonatype.password") as String?,
    System.getenv("SONATYPE_PASSWORD"),
)

// Signing credentials (SIGNING_KEY_ID: 8 hex chars; see normalizePgpKeyIdForGradle)
val signingKeyId: String? = normalizePgpKeyIdForGradle(
    props.getProperty("signing.keyId") ?: System.getenv("SIGNING_KEY_ID"),
)
val signingPassword: String? = (props.getProperty("signing.password") ?: System.getenv("SIGNING_PASSWORD"))?.trim()
val signingSecretKeyRingFile: String? = props.getProperty("signing.secretKeyRingFile") ?: System.getenv("SIGNING_SECRET_KEY_RING_FILE")
val signingSecretKey: String? = System.getenv("SIGNING_SECRET_KEY")?.trim() // ASCII armored or base64

// Project metadata
val projectGroupId = "com.arda"
val projectArtifactId = "compose-code-smell-detector"
val projectVersion = "1.3.7"
val projectName = "DeSmell - Compose Code Smell Detector"
val projectDescription = "Static analysis tool for detecting presentation-layer code smells in Jetpack Compose applications"
val projectUrl = "https://github.com/Arda-Gokalp-Batmaz-AGB/DeSmell-Compose-Code-Smell-Detector"
val projectLicense = "MIT"
val projectLicenseUrl = "https://opensource.org/licenses/MIT"
val developerName = "Arda Gokalp Batmaz"
val developerEmail = props.getProperty("developer.email") ?: System.getenv("DEVELOPER_EMAIL") ?: ""

//
// -------- Signing Configuration (for Maven Central) --------
//
signing {
    // Only sign if credentials are available (required for Maven Central)
    val shouldSign = signingSecretKey != null && signingKeyId != null && signingPassword != null
    
    if (shouldSign) {
        // Use in-memory key from environment variable (CI/CD friendly)
        // The secret key should be base64 encoded or in ASCII-armored format
        useInMemoryPgpKeys(signingKeyId!!, signingSecretKey, signingPassword)
        sign(publishing.publications)
    } else {
        // Try to use GPG command line tool (for local development with GPG installed)
        val keyRingFile = signingSecretKeyRingFile?.let { file(it) }
        if (keyRingFile != null && keyRingFile.exists()) {
            // Configure signing with keyring file
            useGpgCmd()
            sign(publishing.publications)
        }
        // If no signing credentials, skip signing (works for JitPack)
    }
}

//
// -------- Publishing Configuration --------
//
publishing {
    publications {
        create<MavenPublication>("maven") {
            groupId = projectGroupId
            artifactId = projectArtifactId
            version = projectVersion

            from(components["java"])

            pom {
                name.set(projectName)
                description.set(projectDescription)
                url.set(projectUrl)
                
                licenses {
                    license {
                        name.set(projectLicense)
                        url.set(projectLicenseUrl)
                    }
                }
                
                developers {
                    developer {
                        name.set(developerName)
                        email.set(developerEmail)
                    }
                }
                
                scm {
                    connection.set("scm:git:git://github.com/Arda-Gokalp-Batmaz-AGB/DeSmell-Compose-Code-Smell-Detector.git")
                    developerConnection.set("scm:git:ssh://git@github.com/Arda-Gokalp-Batmaz-AGB/DeSmell-Compose-Code-Smell-Detector.git")
                    url.set(projectUrl)
                }
            }
        }
    }

    repositories {
        // Local Maven repository (~/.m2/repository)
        mavenLocal()

        // Maven Central (Sonatype Central Publisher) — use Portal user token, not legacy OSSRH password.
        // https://central.sonatype.org/publish/generate-portal-token/
        // Deploy URL: https://central.sonatype.org/publish/publish-portal-ossrh-staging-api/
        maven {
            name = "Sonatype"
            val releasesRepoUrl =
                "https://ossrh-staging-api.central.sonatype.com/service/local/staging/deploy/maven2/"
            val snapshotsRepoUrl = "https://central.sonatype.com/repository/maven-snapshots/"
            url = uri(if (projectVersion.endsWith("SNAPSHOT")) snapshotsRepoUrl else releasesRepoUrl)

            credentials {
                username = sonatypeUsername ?: ""
                password = sonatypePassword ?: ""
            }
            authentication {
                create<BasicAuthentication>("basic")
            }
        }
        
        // GitHub Packages (BasicAuthentication sends credentials on first request; avoids some 403 responses)
        if (gprUser != null && gprToken != null) {
            maven {
                name = "GitHubPackages"
                url = uri("https://maven.pkg.github.com/Arda-Gokalp-Batmaz-AGB/DeSmell-Compose-Code-Smell-Detector")

                credentials {
                    username = gprUser
                    password = gprToken
                }
                authentication {
                    create<BasicAuthentication>("basic")
                }
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
