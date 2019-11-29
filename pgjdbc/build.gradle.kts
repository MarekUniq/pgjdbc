/*
 * Copyright (c) 2020, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

import aQute.bnd.gradle.Bundle
import aQute.bnd.gradle.BundleTaskConvention
import com.github.vlsi.gradle.dsl.configureEach
import com.github.vlsi.gradle.gettext.GettextTask
import com.github.vlsi.gradle.gettext.MsgAttribTask
import com.github.vlsi.gradle.gettext.MsgFmtTask
import com.github.vlsi.gradle.gettext.MsgMergeTask
import com.github.vlsi.gradle.license.GatherLicenseTask
import com.github.vlsi.gradle.properties.dsl.props
import com.github.vlsi.gradle.release.dsl.dependencyLicenses
import com.github.vlsi.gradle.release.dsl.licensesCopySpec

plugins {
    id("com.github.lburgazzoli.karaf")
    id("com.github.vlsi.gradle-extensions")
    id("com.github.vlsi.gettext")
    id("com.github.johnrengelman.shadow")
    id("biz.aQute.bnd.builder") apply false
}

buildscript {
    repositories {
        // E.g. for biz.aQute.bnd.builder which is not published to Gradle Plugin Portal
        mavenCentral()
    }
}

val shaded by configurations.creating

val karafFeatures by configurations.creating {
    isTransitive = false
}

configurations {
    compileOnly {
        extendsFrom(shaded)
    }
    // Add shaded dependencies to test as well
    // This enables to execute unit tests with original (non-shaded dependencies)
    testImplementation {
        extendsFrom(shaded)
    }
}

val String.v: String get() = rootProject.extra["$this.version"] as String

dependencies {
    shaded(platform(project(":bom")))
    shaded("com.ongres.scram:client")

    // https://github.com/lburgazzoli/gradle-karaf-plugin/issues/75
    karafFeatures(platform(project(":bom")))
    karafFeatures("org.osgi:org.osgi.core:${"org.osgi.core".v}")
    karafFeatures("org.osgi:org.osgi.enterprise:${"org.osgi.enterprise".v}")

    implementation("com.github.waffle:waffle-jna")
    implementation("org.osgi:org.osgi.core")
    implementation("org.osgi:org.osgi.enterprise")
    testImplementation("se.jiderhamn:classloader-leak-test-framework")
}

val skipReplicationTests by props()
val enableGettext by props()

if (skipReplicationTests) {
    tasks.configureEach<Test> {
        exclude("org/postgresql/replication/**")
        exclude("org/postgresql/test/jdbc2/CopyBothResponseTest*")
    }
}

// <editor-fold defaultstate="collapsed" desc="Gettext tasks">
tasks.configureEach<Checkstyle> {
    exclude("**/messages_*")
}

val update_pot_with_new_messages by tasks.registering(GettextTask::class) {
    sourceFiles.from(sourceSets.main.get().allJava)
    keywords.add("GT.tr")
}

val remove_obsolete_translations by tasks.registering(MsgAttribTask::class) {
    args.add("--no-obsolete") // remove obsolete messages
    // TODO: move *.po to resources?
    poFiles.from(files(sourceSets.main.get().allSource).filter { it.path.endsWith(".po") })
}

val add_new_messages_to_po by tasks.registering(MsgMergeTask::class) {
    poFiles.from(remove_obsolete_translations)
    potFile.set(update_pot_with_new_messages.map { it.outputPot.get() })
}

val generate_java_resources by tasks.registering(MsgFmtTask::class) {
    poFiles.from(add_new_messages_to_po)
    targetBundle.set("org.postgresql.translation.messages")
}

val generateGettextSources by tasks.registering {
    group = LifecycleBasePlugin.BUILD_GROUP
    description = "Updates .po, .pot, and .java files in src/main/java/org/postgresql/translation"
    dependsOn(add_new_messages_to_po)
    dependsOn(generate_java_resources)
    doLast {
        copy {
            into("src/main/java")
            from(generate_java_resources)
            into("org/postgresql/translation") {
                from(update_pot_with_new_messages)
                from(add_new_messages_to_po)
            }
        }
    }
}

tasks.compileJava {
    if (enableGettext) {
        dependsOn(generateGettextSources)
    } else {
        mustRunAfter(generateGettextSources)
    }
}
// </editor-fold>

// <editor-fold defaultstate="collapsed" desc="Third-party license gathering">
val getShadedDependencyLicenses by tasks.registering(GatherLicenseTask::class) {
    configuration(shaded)
    extraLicenseDir.set(file("$rootDir/licenses"))
    overrideLicense("com.ongres.scram:common") {
        licenseFiles = "scram"
    }
    overrideLicense("com.ongres.scram:client") {
        licenseFiles = "scram"
    }
    overrideLicense("com.ongres.stringprep:saslprep") {
        licenseFiles = "stringprep"
    }
    overrideLicense("com.ongres.stringprep:stringprep") {
        licenseFiles = "stringprep"
    }
}

val renderShadedLicense by tasks.registering(com.github.vlsi.gradle.release.Apache2LicenseRenderer::class) {
    group = LifecycleBasePlugin.BUILD_GROUP
    description = "Generate LICENSE file for shaded jar"
    mainLicenseFile.set(File(rootDir, "LICENSE"))
    failOnIncompatibleLicense.set(false)
    artifactType.set(com.github.vlsi.gradle.release.ArtifactType.BINARY)
    metadata.from(getShadedDependencyLicenses)
}

val shadedLicenseFiles = licensesCopySpec(renderShadedLicense)
// </editor-fold>

tasks.configureEach<Jar> {
    manifest {
        attributes["Main-Class"] = "org.postgresql.util.PGJDBCMain"
        attributes["Automatic-Module-Name"] = "org.postgresql.jdbc"
    }
}

tasks.shadowJar {
    configurations = listOf(shaded)
    exclude("META-INF/maven/**")
    exclude("META-INF/LICENSE*")
    exclude("META-INF/NOTICE*")
    into("META-INF") {
        dependencyLicenses(shadedLicenseFiles)
    }
    listOf(
            "com.ongres"
    ).forEach {
        relocate(it, "${project.group}.shaded.$it")
    }
}

val osgiJar by tasks.registering(Bundle::class) {
    archiveClassifier.set("osgi")
    from(tasks.shadowJar.map { zipTree(it.archiveFile) })
    withConvention(BundleTaskConvention::class) {
        bnd(
            """
            -exportcontents: !org.postgresql.shaded.*, org.postgresql.*
            -removeheaders: Created-By
            Bundle-Descriptiona: Java JDBC driver for PostgreSQL database
            Bundle-DocURL: https://jdbc.postgresql.org/
            Bundle-Vendor: PostgreSQL Global Development Group
            Import-Package: javax.sql, javax.transaction.xa, javax.naming, javax.security.sasl;resolution:=optional, *;resolution:=optional
            Bundle-Activator: org.postgresql.osgi.PGBundleActivator
            Bundle-SymbolicName: org.postgresql.jdbc
            Bundle-Name: PostgreSQL JDBC Driver
            Bundle-Copyright: Copyright (c) 2003-2020, PostgreSQL Global Development Group
            Require-Capability: osgi.ee;filter:="(&(|(osgi.ee=J2SE)(osgi.ee=JavaSE))(version>=1.8))"
            Provide-Capability: osgi.service;effective:=active;objectClass=org.osgi.service.jdbc.DataSourceFactory
            """
        )
    }
}

karaf {
    features.apply {
        xsdVersion = "1.5.0"
        feature(closureOf<com.github.lburgazzoli.gradle.plugin.karaf.features.model.FeatureDescriptor> {
            name = "postgresql"
            description = "PostgreSQL JDBC driver karaf feature"
            version = project.version.toString()
            details = "Java JDBC 4.2 (JRE 8+) driver for PostgreSQL database"
            feature("transaction-api")
            includeProject = true
            bundle(project.group.toString(), closureOf<com.github.lburgazzoli.gradle.plugin.karaf.features.model.BundleDescriptor> {
                wrap = false
            })
            // List argument clears the "default" configurations
            configurations(listOf(karafFeatures))
        })
    }
}

val extraMavenPublications by configurations.getting

(artifacts) {
    extraMavenPublications(osgiJar) {
        classifier = ""
    }
    extraMavenPublications(karaf.features.outputFile) {
        builtBy(tasks.named("generateFeatures"))
        classifier = "features"
    }
}