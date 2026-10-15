plugins { `kotlin-dsl` }
repositories { mavenCentral(); gradlePluginPortal() }

// Tests of the egress check's class file reader and allowlist (./gradlew :buildSrc:test). JUnit 4.13.2 is the version
// every module already tests with (libs.junit; buildSrc cannot read the version catalog), in gradle/verification-metadata.xml.
dependencies { testImplementation("junit:junit:4.13.2") }
