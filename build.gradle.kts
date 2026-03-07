plugins {
    java
    application
}

group = "org.unicitylabs"
version = "1.0.0"

java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}

repositories {
    mavenCentral()
    maven { url = uri("https://jitpack.io") }
}

dependencies {
    // Unicity Nostr SDK (includes Nostr client, crypto, nametag binding, token transfer)
    // Version 0.2.4 adds auto-reconnect with exponential backoff
    implementation("org.unicitylabs:nostr-sdk:0.4.0-dev.1")

    // Unicity SDK
    implementation("org.unicitylabs:java-state-transition-sdk:1.4.2")

    // Jackson for JSON and CBOR (also used by Nostr SDK and SDK)
    implementation("com.fasterxml.jackson.core:jackson-databind:2.17.0")
    implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-cbor:2.17.0")
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jdk8:2.17.0")

    // Logging
    implementation("org.slf4j:slf4j-api:2.0.9")
    implementation("ch.qos.logback:logback-classic:1.4.11")

    // CLI argument parsing
    implementation("info.picocli:picocli:4.7.5")

    // BIP-39 for mnemonic phrase (pure Java)
    implementation("org.bitcoinj:bitcoinj-core:0.16.3")

    // Apache Commons Codec for hex encoding (used by SDK and faucet)
    implementation("commons-codec:commons-codec:1.16.0")

    // Web server framework
    implementation("io.javalin:javalin:5.6.3")

    // SQLite database
    implementation("org.xerial:sqlite-jdbc:3.44.1.0")

    // Testing
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.awaitility:awaitility:4.2.0")
}

application {
    mainClass.set("org.unicitylabs.faucet.FaucetCLI")
}

// Create a fat JAR with all dependencies
tasks.jar {
    manifest {
        attributes["Main-Class"] = "org.unicitylabs.faucet.FaucetServer"
    }
    // Include all dependencies
    from(configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) })
    // Exclude signature files to avoid signature conflicts
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}

tasks.register<JavaExec>("mint") {
    group = "application"
    description = "Mint and send a token via Nostr"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("org.unicitylabs.faucet.FaucetCLI")

    // Allow passing arguments: ./gradlew mint --args="--nametag=alice --amount=100"
    if (project.hasProperty("args")) {
        args = (project.property("args") as String).split("\\s+".toRegex())
    }
}

tasks.register<JavaExec>("server") {
    group = "application"
    description = "Run the faucet web server"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("org.unicitylabs.faucet.FaucetServer")
}
