import java.util.Properties

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
file("$rootDir/local.properties").takeIf { it.exists() }?.inputStream()?.use { props.load(it) }

// GitHub Packages credentials
val gprUser: String? = props.getProperty("gpr.user") ?: System.getenv("GPR_USER")
val gprToken: String? = props.getProperty("gpr.token") ?: System.getenv("GPR_TOKEN")

// Maven Central credentials
val sonatypeUsername: String? = props.getProperty("sonatype.username") ?: System.getenv("SONATYPE_USERNAME")
val sonatypePassword: String? = props.getProperty("sonatype.password") ?: System.getenv("SONATYPE_PASSWORD")

// Signing credentials
val signingKeyId: String? = props.getProperty("signing.keyId") ?: System.getenv("SIGNING_KEY_ID")
val signingPassword: String? = props.getProperty("signing.password") ?: System.getenv("SIGNING_PASSWORD")
val signingSecretKeyRingFile: String? = props.getProperty("signing.secretKeyRingFile") ?: System.getenv("SIGNING_SECRET_KEY_RING_FILE")
val signingSecretKey: String? = System.getenv("SIGNING_SECRET_KEY") // Base64 encoded key

// Project metadata
val projectGroupId = "com.arda"
val projectArtifactId = "compose-code-smell-detector"
val projectVersion = "1.3.5"
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
        useInMemoryPgpKeys(signingKeyId, signingSecretKey, signingPassword)
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
                    developerConnection.set("scm:git:ssh://github.com:Arda-Gokalp-Batmaz-AGB/DeSmell-Compose-Code-Smell-Detector.git")
                    url.set(projectUrl)
                }
            }
        }
    }

    repositories {
        // Maven Central (Sonatype)
        if (sonatypeUsername != null && sonatypePassword != null) {
            maven {
                name = "Sonatype"
                val releasesRepoUrl = "https://s01.oss.sonatype.org/service/local/staging/deploy/maven2/"
                val snapshotsRepoUrl = "https://s01.oss.sonatype.org/content/repositories/snapshots/"
                url = uri(if (projectVersion.endsWith("SNAPSHOT")) snapshotsRepoUrl else releasesRepoUrl)
                
                credentials {
                    username = sonatypeUsername
                    password = sonatypePassword
                }
            }
        }
        
        // GitHub Packages
        if (gprUser != null && gprToken != null) {
            maven {
                name = "GitHubPackages"
                url = uri("https://maven.pkg.github.com/Arda-Gokalp-Batmaz-AGB/DeSmell-Compose-Code-Smell-Detector")
                
                credentials {
                    username = gprUser
                    password = gprToken
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
