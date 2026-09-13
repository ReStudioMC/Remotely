import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import org.gradle.api.file.DuplicatesStrategy
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.tasks.Sync
import org.gradle.jvm.toolchain.JvmVendorSpec
import java.io.RandomAccessFile
import java.util.ArrayDeque
import java.util.UUID
import java.util.zip.ZipFile

plugins {
    id("java-library")
    id("application")
    id("maven-publish")
    id("com.gradleup.shadow") version "8.3.8"
}

group = "redxax.oxy"
version = property("remotely.version").toString()
val useReStudioSourceDependencies = extra["reStudioSourceDependencies"] as Boolean

application {
    mainClass.set("redxax.oxy.remotely.RemotelyInit")
}

val reStudioReleaseJarTasks: List<Any> = if (useReStudioSourceDependencies) {
    listOf(
        gradle.includedBuild("ReScreen").task(":jar"),
        gradle.includedBuild("Remodel").task(":jar"),
        gradle.includedBuild("Rebase").task(":jar"),
        gradle.includedBuild("ReSync").task(":ReSyncCore:jar")
    )
} else {
    emptyList()
}

val reStudioSourceJars = files(
    "../ReScreen/build/libs/ReScreen-1.0.jar",
    "../Remodel/build/libs/Remodel-1.0.0.jar",
    "../Rebase/build/libs/Rebase-1.0-SNAPSHOT.jar",
    "../ReSync/ReSyncCore/build/libs/ReSyncCore-1.3.0.jar"
)

val releaseRequiredClasses = listOf(
    "restudio/rescreen/config/UiConfigStore.class",
    "restudio/rebase/Rebase.class",
    "redxax/restudio/Remodel/Main.class",
    "restudio/resync/network/NetworkFrame.class"
)

fun requireReleaseClasses(archives: Collection<File>, artifactName: String) {
    val jarFiles = archives.filter { it.isFile && it.extension.equals("jar", ignoreCase = true) }
    val missingClasses = releaseRequiredClasses.filter { className ->
        jarFiles.none { archive -> ZipFile(archive).use { it.getEntry(className) != null } }
    }
    require(missingClasses.isEmpty()) {
        "$artifactName Is Missing Required Classes: ${missingClasses.joinToString()}"
    }
}

val remotelySnakeYaml by configurations.creating {
    isCanBeResolved = true
    isCanBeConsumed = false
}

val relocatedSnakeYaml by tasks.registering(ShadowJar::class) {
    archiveFileName.set("remotely-snakeyaml-2.6.jar")
    destinationDirectory.set(layout.buildDirectory.dir("relocated-inputs"))
    configurations = listOf(remotelySnakeYaml)
    exclude("**/module-info.class")
    exclude("META-INF/versions/**")
    relocate("org.yaml.snakeyaml", "redxax.oxy.remotely.libs.snakeyaml")
}

data class BrowserJavaSource(val className: String, val packageName: String, val relativePath: String, val file: File)

val browserMainSourceRoot = file("src/main/java")
val browserOwnPrefix = "redxax.oxy.remotely"
val browserSources = linkedMapOf<String, BrowserJavaSource>()

fun indexBrowserSources(root: File) {
    if (!root.isDirectory) return
    root.walkTopDown().filter { it.isFile && it.extension == "java" }.sortedBy { it.invariantSeparatorsPath }.forEach { source ->
        val relativePath = source.relativeTo(root).invariantSeparatorsPath
        if (!relativePath.startsWith("redxax/oxy/remotely/")) return@forEach
        val className = relativePath.removeSuffix(".java").replace('/', '.')
        browserSources[className] = BrowserJavaSource(className, className.substringBeforeLast('.', ""), relativePath, source)
    }
}

indexBrowserSources(browserMainSourceRoot)

val browserSourceAliases = linkedMapOf<String, BrowserJavaSource>()
val browserTypeDeclaration = Regex("""\b(?:class|interface|enum|record)\s+([A-Za-z_$][A-Za-z0-9_$]*)""")
browserSources.values.sortedBy { it.className }.forEach { source ->
    browserSourceAliases[source.className] = source
    browserTypeDeclaration.findAll(source.file.readText()).forEach { declaration ->
        browserSourceAliases.putIfAbsent("${source.packageName}.${declaration.groupValues[1]}", source)
    }
}
val browserAliasesByPackage = browserSourceAliases.entries.groupBy { it.key.substringBeforeLast('.', "") }

fun resolveBrowserSource(reference: String): BrowserJavaSource? {
    var candidate = reference.replace('$', '.')
    while (candidate.startsWith(browserOwnPrefix)) {
        browserSourceAliases[candidate]?.let { return it }
        val separator = candidate.lastIndexOf('.')
        if (separator < browserOwnPrefix.length) return null
        candidate = candidate.substring(0, separator)
    }
    return null
}

fun browserSourceText(raw: String): String {
    val text = StringBuilder(raw.length)
    var state = 0
    var escaped = false
    var index = 0
    while (index < raw.length) {
        val character = raw[index]
        val next = raw.getOrNull(index + 1)
        when (state) {
            0 -> when {
                character == '/' && next == '/' -> {
                    text.append("  ")
                    state = 1
                    index++
                }
                character == '/' && next == '*' -> {
                    text.append("  ")
                    state = 2
                    index++
                }
                character == '"' -> {
                    text.append(' ')
                    state = 3
                    escaped = false
                }
                character == '\'' -> {
                    text.append(' ')
                    state = 4
                    escaped = false
                }
                else -> text.append(character)
            }
            1 -> {
                text.append(if (character == '\n' || character == '\r') character else ' ')
                if (character == '\n' || character == '\r') state = 0
            }
            2 -> {
                if (character == '*' && next == '/') {
                    text.append("  ")
                    state = 0
                    index++
                } else {
                    text.append(if (character == '\n' || character == '\r') character else ' ')
                }
            }
            else -> {
                text.append(if (character == '\n' || character == '\r') character else ' ')
                if (escaped) escaped = false
                else if (character == '\\') escaped = true
                else if (state == 3 && character == '"' || state == 4 && character == '\'') state = 0
            }
        }
        index++
    }
    return text.toString()
}

fun browserSourceDependencies(source: BrowserJavaSource, missing: MutableSet<String>): Set<BrowserJavaSource> {
    val text = browserSourceText(source.file.readText())
    val simpleNames = Regex("""\b[A-Za-z_$][A-Za-z0-9_$]*\b""").findAll(text).map { it.value }.toHashSet()
    val dependencies = linkedSetOf<BrowserJavaSource>()
    val wildcardPackages = linkedSetOf<String>()
    Regex("""(?m)^\s*import\s+((?:static\s+)?[^;]+);""").findAll(text).forEach { match ->
        var imported = match.groupValues[1].removePrefix("static ").trim()
        if (!imported.startsWith(browserOwnPrefix)) return@forEach
        if (imported.startsWith("redxax.oxy.remotely.libs.")) return@forEach
        if (imported.endsWith(".*") && !match.groupValues[1].startsWith("static ")) {
            wildcardPackages += imported.removeSuffix(".*")
            return@forEach
        }
        if (imported.endsWith(".*")) imported = imported.removeSuffix(".*")
        val resolved = resolveBrowserSource(imported)
        if (resolved == null) missing += "${source.className} -> $imported"
        else if (resolved != source) dependencies += resolved
    }
    Regex("""redxax\.oxy\.remotely(?:\.[A-Za-z_$][A-Za-z0-9_$]*)+""").findAll(text).forEach { match ->
        resolveBrowserSource(match.value)?.takeIf { it != source }?.let(dependencies::add)
    }
    browserAliasesByPackage[source.packageName].orEmpty().asSequence()
        .filter { (name, dependency) -> dependency != source && simpleNames.contains(name.substringAfterLast('.')) }
        .map { it.value }.forEach(dependencies::add)
    wildcardPackages.forEach { importedPackage ->
        browserAliasesByPackage[importedPackage].orEmpty().asSequence()
            .filter { (name, dependency) -> dependency != source && simpleNames.contains(name.substringAfterLast('.')) }
            .map { it.value }.forEach(dependencies::add)
    }
    return dependencies
}

val browserCanonicalRoots = linkedSetOf(
    "redxax.oxy.remotely.RemotelyServerApi",
    "redxax.oxy.remotely.host.ApplicationHost",
    "redxax.oxy.remotely.host.ApplicationHostRegistry",
    "redxax.oxy.remotely.data.flow.FlowManager",
    "redxax.oxy.remotely.flow.ui.FlowEditorScreen",
    "redxax.oxy.remotely.flow.ui.studio.StudioScreen",
    "redxax.oxy.remotely.flow.ui.marketplace.ReSyncMarketplaceApi",
    "redxax.oxy.remotely.flow.ui.marketplace.ReSyncMarketplaceScreen",
    "redxax.oxy.remotely.worldgen.WorldGenManager",
    "redxax.oxy.remotely.worldgen.ui.WorldGenEditorScreen",
    "redxax.oxy.remotely.ui.server.ServerManagerScreen",
    "redxax.oxy.remotely.ui.server.ServerDetailsScreen",
    "redxax.oxy.remotely.ui.server.ServerTerminal"
)
val browserWebSourceRoot = file("RemotelyWeb/src/main/java")
if (browserWebSourceRoot.isDirectory) {
    val ownImport = Regex("""(?m)^\s*import\s+(redxax\.oxy\.remotely\.(?!web\.)[A-Za-z0-9_$.]+)\s*;""")
    browserWebSourceRoot.walkTopDown().filter { it.isFile && it.extension == "java" }.sortedBy { it.invariantSeparatorsPath }.forEach { source ->
        ownImport.findAll(source.readText()).map { it.groupValues[1] }.mapNotNull(::resolveBrowserSource).map { it.className }
            .forEach(browserCanonicalRoots::add)
    }
}

val missingBrowserSources = linkedSetOf<String>()
val browserRequiredSources = linkedSetOf<BrowserJavaSource>()
val browserSourceQueue = ArrayDeque<BrowserJavaSource>()
fun isBrowserSource(source: BrowserJavaSource): Boolean =
    !source.file.name.contains("Desktop") && !source.file.invariantSeparatorsPath.contains("/platform/jvm/")
browserCanonicalRoots.sorted().forEach { root ->
    val source = resolveBrowserSource(root)
    if (source == null) missingBrowserSources += "Required browser root is missing: $root"
    else if (!isBrowserSource(source)) missingBrowserSources += "Required browser root is a desktop adapter: $root"
    else if (browserRequiredSources.add(source)) browserSourceQueue.addLast(source)
}
while (browserSourceQueue.isNotEmpty()) {
    val source = browserSourceQueue.removeFirst()
    browserSourceDependencies(source, missingBrowserSources).sortedBy { it.className }.forEach { dependency ->
        if (isBrowserSource(dependency) && browserRequiredSources.add(dependency)) browserSourceQueue.addLast(dependency)
    }
}
require(missingBrowserSources.isEmpty()) {
    "Remotely Browser Source Closure Is Incomplete:\n${missingBrowserSources.sorted().joinToString("\n")}"
}
val browserDesktopOnlyClasses = setOf(
    "redxax.oxy.remotely.servers.QuickServerSyncManager",
    "redxax.oxy.remotely.servers.ReProxyAutoStartService",
    "redxax.oxy.remotely.servers.ReProxyManager",
    "redxax.oxy.remotely.servers.ReverseProxyManager"
)
val browserSourceIncludes = browserRequiredSources.filterNot { it.className in browserDesktopOnlyClasses }.map { it.relativePath }.toSortedSet()

val browser by sourceSets.creating {
    java.srcDir(browserMainSourceRoot)
    java.include(browserSourceIncludes)
    resources.srcDir("src/main/resources")
    resources.include("server-settings/**")
}

dependencies {
    add(browser.implementationConfigurationName, "com.google.code.gson:gson:2.10.1")
    if (useReStudioSourceDependencies) {
        add(browser.implementationConfigurationName, files(
            "../ReScreen/build/libs/ReScreen-1.0-browser.jar",
            "../Rebase/build/libs/Rebase-1.0-SNAPSHOT-browser.jar",
            "../ReSync/ReSyncCore/build/libs/ReSyncCore-1.3.0-browser.jar"
        ))
    } else {
        add(browser.implementationConfigurationName, "dev.restudio:rescreen:1.0:browser")
        add(browser.implementationConfigurationName, "dev.restudio:rebase:1.0-SNAPSHOT:browser")
        add(browser.implementationConfigurationName, "restudio.resync:ReSyncCore:1.3.0:browser")
    }
}

tasks.named(browser.compileJavaTaskName) {
    if (useReStudioSourceDependencies) {
        dependsOn(
            gradle.includedBuild("ReScreen").task(":browserJar"),
            gradle.includedBuild("Rebase").task(":browserJar"),
            gradle.includedBuild("ReSync").task(":ReSyncCore:browserJar")
        )
    }
}

val browserJar by tasks.registering(Jar::class) {
    archiveClassifier.set("browser")
    from(browser.output)
    doLast {
        val archive = archiveFile.get().asFile
        val classes = linkedMapOf<String, ByteArray>()
        ZipFile(archive).use { zip ->
            zip.entries().asSequence().filter { !it.isDirectory && it.name.endsWith(".class") }.forEach { entry ->
                classes[entry.name.removeSuffix(".class")] = zip.getInputStream(entry).readBytes()
            }
        }
        val requiredRoots = browserCanonicalRoots.map { it.replace('.', '/') }.toSortedSet()
        val absentRoots = requiredRoots.filterNot(classes::containsKey)
        require(absentRoots.isEmpty()) { "Remotely Browser Artifact Is Missing Required Roots:\n${absentRoots.joinToString("\n")}" }
        val ownReference = Regex("""redxax/oxy/remotely/[A-Za-z0-9_$/]+""")
        val externalReference = Regex("""restudio/(?:rebase|rescreen|resync)/[A-Za-z0-9_$/]+""")
        val references = classes.mapValues { (_, bytes) ->
            ownReference.findAll(bytes.toString(Charsets.ISO_8859_1)).map { it.value }.toSortedSet()
        }
        val missingClasses = references.flatMap { (owner, dependencies) ->
            dependencies.filterNot(classes::containsKey).map { "$owner -> $it" }
        }.toSortedSet()
        require(missingClasses.isEmpty()) { "Remotely Browser Artifact Has Missing Own Classes:\n${missingClasses.joinToString("\n")}" }
        val reachable = linkedSetOf<String>()
        val queue = ArrayDeque(requiredRoots)
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            if (!reachable.add(current)) continue
            references[current].orEmpty().filter(classes::containsKey).filterNot(reachable::contains).forEach(queue::addLast)
        }
        val unreachable = classes.keys.filterNot(reachable::contains).sorted()
        require(unreachable.isEmpty()) { "Remotely Browser Artifact Contains Unreachable Classes:\n${unreachable.joinToString("\n")}" }
        val forbiddenSymbols = listOf(
            "java/awt/", "javax/sound/", "java/net/http/", "java/lang/Process", "java/nio/file/", "java/util/concurrent/",
            "java/lang/reflect/", "java/util/ServiceLoader",
            "restudio/rebase/platform/jvm/", "restudio/rescreen/platform/lwjgl/", "org/lwjgl/", "com/sun/jna/", "com/pty4j/",
            "com/jediterm/", "org/gradle/", "net/schmizz/sshj/"
        )
        val forbidden = classes.flatMap { (className, bytes) ->
            val symbols = bytes.toString(Charsets.ISO_8859_1)
            buildList {
                forbiddenSymbols.filter(symbols::contains).forEach { add("$className -> $it") }
                if (symbols.contains("java/lang/Class") && symbols.contains("forName")) add("$className -> java/lang/Class.forName")
                if (className.substringAfterLast('/').startsWith("Desktop")) add("$className -> Desktop Adapter")
            }
        }.toSortedSet()
        val directExternalRoots = classes.values.asSequence().flatMap { bytes ->
            externalReference.findAll(bytes.toString(Charsets.ISO_8859_1)).map { it.value }
        }.toSortedSet()
        val report = layout.buildDirectory.file("reports/remotely-browser-direct-roots.txt").get().asFile
        report.parentFile.mkdirs()
        report.writeText(directExternalRoots.joinToString("\n", postfix = if (directExternalRoots.isEmpty()) "" else "\n"))
        require(forbidden.isEmpty()) { "Remotely Browser Artifact Contains Forbidden Symbols:\n${forbidden.joinToString("\n")}" }
    }
}

val browserElements by configurations.creating {
    isCanBeConsumed = true
    isCanBeResolved = false
}

artifacts {
    add(browserElements.name, browserJar)
}

tasks.processResources {
    dependsOn(relocatedSnakeYaml)
    from(relocatedSnakeYaml.map { zipTree(it.archiveFile.get().asFile) }) {
        exclude("META-INF/MANIFEST.MF")
    }
}

dependencies {
    add("compileOnly", files(relocatedSnakeYaml))
}

tasks.compileJava {
    dependsOn(relocatedSnakeYaml)
    if (useReStudioSourceDependencies) {
        dependsOn(reStudioReleaseJarTasks)
    }
}

val sourceRuntimeInputs = linkedMapOf(
    "Remotely" to listOf("build/classes/java/main", "build/resources/main"),
    "ReScreen" to listOf("../ReScreen/build/classes/java/main", "../ReScreen/build/resources/main"),
    "Rebase" to listOf("../Rebase/build/classes/java/main", "../Rebase/build/resources/main"),
    "Remodel" to listOf("../Remodel/build/classes/java/main", "../Remodel/build/resources/main"),
    "ReSyncCore" to listOf("../ReSync/ReSyncCore/build/classes/java/main", "../ReSync/ReSyncCore/build/resources/main")
)
val sourceRuntimeSnapshot = layout.projectDirectory.dir(".gradle/run-classpath/${UUID.randomUUID()}")
val stageSourceRuntime = tasks.register<Sync>("stageSourceRuntime") {
    if (useReStudioSourceDependencies) {
        dependsOn(
            tasks.named("classes"),
            gradle.includedBuild("ReScreen").task(":classes"),
            gradle.includedBuild("Rebase").task(":classes"),
            gradle.includedBuild("Remodel").task(":classes"),
            gradle.includedBuild("ReSync").task(":ReSyncCore:classes")
        )
        sourceRuntimeInputs.forEach { (module, paths) ->
            paths.forEach { path ->
                from(path) {
                    into(module)
                }
            }
        }
        into(sourceRuntimeSnapshot)
    }
}

tasks.named<JavaExec>("run") {
    if (useReStudioSourceDependencies) {
        dependsOn(stageSourceRuntime)
        val localProjectOutputs = files(sourceRuntimeInputs.values.flatten().map(::file))
        val externalRuntime = provider {
            configurations.runtimeClasspath.get().files.filter {
                val path = it.absolutePath.replace('\\', '/')
                !path.contains("/ReScreen/build/libs/") &&
                    !path.contains("/Rebase/build/libs/") &&
                    !path.contains("/Remodel/build/libs/") &&
                    !path.contains("/ReSync/ReSyncCore/build/libs/")
            }
        }
        classpath = files(localProjectOutputs, externalRuntime)
    }
}

if (useReStudioSourceDependencies) {
    tasks.register<JavaExec>("runLive") {
        val launchAgent = layout.buildDirectory.file("live-refresh/rescreen-live-agent-${UUID.randomUUID()}.jar")
        group = "development"
        description = "Runs Remotely with ReScreen live refresh."
        mainClass.set(application.mainClass)
        javaLauncher.set(javaToolchains.launcherFor {
            languageVersion.set(JavaLanguageVersion.of(21))
            vendor.set(JvmVendorSpec.JETBRAINS)
        })
        dependsOn(
            tasks.named("classes"),
            gradle.includedBuild("ReScreen").task(":classes"),
            gradle.includedBuild("ReScreen").task(":liveAgentJar"),
            gradle.includedBuild("Rebase").task(":classes"),
            gradle.includedBuild("Remodel").task(":classes"),
            gradle.includedBuild("ReSync").task(":ReSyncCore:classes")
        )
        val localProjectOutputs = files(sourceRuntimeInputs.values.flatten().map(::file))
        val externalRuntime = configurations.runtimeClasspath.get().files.filter {
            val path = it.absolutePath.replace('\\', '/')
            !path.contains("/ReScreen/build/libs/") &&
                !path.contains("/Rebase/build/libs/") &&
                !path.contains("/Remodel/build/libs/") &&
                !path.contains("/ReSync/ReSyncCore/build/libs/")
        }
        classpath = files(localProjectOutputs, externalRuntime)
        doFirst {
            val agent = launchAgent.get().asFile
            agent.parentFile.mkdirs()
            layout.projectDirectory.file("../ReScreen/build/libs/rescreen-live-agent-build.jar").asFile.copyTo(agent, overwrite = true)
            val livePaths = localProjectOutputs.files.joinToString(File.pathSeparator) { it.absolutePath }
            jvmArgs(
                "-XX:+IgnoreUnrecognizedVMOptions",
                "-XX:+AllowEnhancedClassRedefinition",
                "-javaagent:${agent.absolutePath}",
                "-Drescreen.live.paths=$livePaths"
            )
        }
        doLast {
            launchAgent.get().asFile.delete()
        }
    }
}

repositories {
    mavenLocal {
        content {
            includeGroup("dev.restudio")
            includeGroup("restudio.resync")
        }
    }
    mavenCentral()
    maven("https://repo.gradle.org/gradle/libs-releases")
    maven("https://maven.scijava.org/content/repositories/public/")
    maven("https://packages.jetbrains.team/maven/p/ij/intellij-dependencies")
    maven("https://jitpack.io/")
}

publishing {
    publications {
        create<MavenPublication>("restudio") {
            from(components["java"])
            groupId = "dev.restudio"
            artifactId = "remotely-app"
        }
    }
}

dependencies {
    if (useReStudioSourceDependencies) {
        api(reStudioSourceJars)
        api("dev.restudio:rescreen:1.0")
        api("dev.restudio:remodel:1.0.0")
        api("dev.restudio:rebase:1.0-SNAPSHOT")
        implementation("restudio.resync:ReSyncCore:1.3.0")
    } else if (reStudioSourceJars.files.all { it.isFile }) {
        api(reStudioSourceJars)
    } else {
        api("dev.restudio:rescreen:1.0")
        api("dev.restudio:remodel:1.0.0")
        api("dev.restudio:rebase:1.0-SNAPSHOT")
        implementation("restudio.resync:ReSyncCore:1.3.0")
    }

    implementation("org.eclipse.lsp4j:org.eclipse.lsp4j:0.24.0")
    implementation("org.eclipse.lsp4j:org.eclipse.lsp4j.jsonrpc:0.24.0")

    implementation("com.google.code.gson:gson:2.10.1")
    implementation("net.kyori:adventure-text-minimessage:4.25.0")
    implementation("net.kyori:adventure-text-serializer-legacy:4.25.0")
    implementation("io.github.canary-prism:querz-nbt:6.2.1")
    implementation("com.twelvemonkeys.imageio:imageio-webp:3.12.0")
    implementation("org.apache.commons:commons-compress:1.28.0")
    remotelySnakeYaml("org.yaml:snakeyaml:2.6")

    implementation("com.vladsch.flexmark:flexmark:0.64.8")
    implementation("com.vladsch.flexmark:flexmark-ext-autolink:0.64.8")
    implementation("com.vladsch.flexmark:flexmark-ext-gfm-strikethrough:0.64.8")
    implementation("com.vladsch.flexmark:flexmark-ext-gfm-tasklist:0.64.8")
    implementation("com.vladsch.flexmark:flexmark-ext-ins:0.64.8")
    implementation("com.vladsch.flexmark:flexmark-ext-tables:0.64.8")
    implementation("org.jsoup:jsoup:1.23.1")

    implementation("org.apache.xmlgraphics:batik-transcoder:1.19")
    implementation("com.googlecode.soundlibs:vorbisspi:1.0.3.3")
    implementation("com.github.javakeyring:java-keyring:1.0.4")
    implementation("net.java.dev.jna:jna-platform:5.13.0")
    implementation("com.hierynomus:sshj:0.40.0")
    implementation("com.github.JnCrMx:discord-game-sdk4j:1.0.0")

    implementation("org.jetbrains.pty4j:pty4j:0.13.10-1")
    implementation("org.jetbrains.jediterm:jediterm-core:3.54")
    implementation("org.jetbrains.jediterm:jediterm-pty:2.69")

    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")

    val lwjglVersion = "3.3.6"
    implementation(platform("org.lwjgl:lwjgl-bom:$lwjglVersion"))
    implementation("org.lwjgl:lwjgl")
    implementation("org.lwjgl:lwjgl-opengl")
    implementation("org.lwjgl:lwjgl-stb")
    implementation("org.lwjgl:lwjgl-glfw")
    implementation("org.joml:joml:1.9.25")

    runtimeOnly("org.lwjgl:lwjgl::natives-windows")
    runtimeOnly("org.lwjgl:lwjgl-opengl::natives-windows")
    runtimeOnly("org.lwjgl:lwjgl-stb::natives-windows")
    runtimeOnly("org.lwjgl:lwjgl-glfw::natives-windows")

    runtimeOnly("org.lwjgl:lwjgl::natives-linux")
    runtimeOnly("org.lwjgl:lwjgl-opengl::natives-linux")
    runtimeOnly("org.lwjgl:lwjgl-stb::natives-linux")
    runtimeOnly("org.lwjgl:lwjgl-glfw::natives-linux")

    runtimeOnly("org.lwjgl:lwjgl::natives-macos")
    runtimeOnly("org.lwjgl:lwjgl-opengl::natives-macos")
    runtimeOnly("org.lwjgl:lwjgl-stb::natives-macos")
    runtimeOnly("org.lwjgl:lwjgl-glfw::natives-macos")

    runtimeOnly("org.lwjgl:lwjgl::natives-macos-arm64")
    runtimeOnly("org.lwjgl:lwjgl-opengl::natives-macos-arm64")
    runtimeOnly("org.lwjgl:lwjgl-stb::natives-macos-arm64")
    runtimeOnly("org.lwjgl:lwjgl-glfw::natives-macos-arm64")
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

tasks {
    test {
        useJUnitPlatform()
    }
}

if (useReStudioSourceDependencies) {
    tasks.register("publishSourceDependenciesToMavenLocal") {
        group = "publishing"
        dependsOn(
            gradle.includedBuild("Remodel").task(":publishToMavenLocal"),
            gradle.includedBuild("ReScreen").task(":publishToMavenLocal"),
            gradle.includedBuild("Rebase").task(":publishToMavenLocal"),
            gradle.includedBuild("ReSync").task(":ReSyncCore:publishToMavenLocal")
        )
    }
}

tasks.register<JavaExec>("reSyncProductionAcceptance") {
    group = "verification"
    description = "Runs the production ReSync client against a live server or its offline registry cache."
    dependsOn(tasks.named("testClasses"))
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("redxax.oxy.remotely.data.flow.ReSyncProductionAcceptanceMain")
    systemProperty("user.home", providers.gradleProperty("acceptanceHome").orElse(layout.buildDirectory.dir("resync-acceptance-home").map { it.asFile.absolutePath }).get())
}

tasks.jar {
    manifest {
        attributes["Main-Class"] = "redxax.oxy.remotely.RemotelyInit"
        attributes["Implementation-Version"] = project.version.toString()
        attributes["Automatic-Module-Name"] = "dev.restudio.remotely.app"
        attributes["Enable-Native-Access"] = "ALL-UNNAMED"
    }
    doFirst {
        manifest {
            attributes["Class-Path"] = configurations.runtimeClasspath.get().joinToString(separator = " ") { it.name }
        }
    }
    archiveFileName.set("Remotely-App.jar")
}

tasks.register<Jar>("fatJar") {
    group = "build"
    dependsOn(reStudioReleaseJarTasks)
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    archiveFileName.set("Remotely-Fat.jar")
    from(sourceSets.main.get().output)
    from({
        configurations.runtimeClasspath.get().filter { it.exists() }.map {
            if (it.isDirectory) it else zipTree(it)
        }
    })
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")
    manifest {
        attributes["Main-Class"] = "redxax.oxy.remotely.RemotelyInit"
        attributes["Implementation-Version"] = project.version.toString()
        attributes["Automatic-Module-Name"] = "dev.restudio.remotely.app"
        attributes["Enable-Native-Access"] = "ALL-UNNAMED"
    }
    doLast {
        requireReleaseClasses(listOf(archiveFile.get().asFile), "Remotely Fat Jar")
    }
}

val packageJarName = "Remotely-App.jar"
val cleanVersion = version.toString().substringBefore('-').replace(Regex("[^0-9.]"), "")
val developerWindowsUpgradeUuid = UUID.nameUUIDFromBytes("net.restudiomc.remotely.windows.development".toByteArray()).toString()

fun developmentInstallerVersion(version: String, buildNumber: Int): String {
    require(buildNumber in 1..65535) { "Installer build number must be between 1 and 65535" }
    val components = version.split('.').filter(String::isNotBlank)
    val major = components.getOrElse(0) { "0" }
    val minor = components.getOrElse(1) { "0" }
    return "$major.$minor.$buildNumber"
}

fun nextLocalInstallerBuildNumber(version: String): Int {
    val baseVersion = version.split('.').filter(String::isNotBlank).take(2).joinToString(".").ifBlank { "0.0" }
    val stateFile = layout.projectDirectory.file(".gradle/remotely-installer-build-number").asFile
    stateFile.parentFile.mkdirs()

    return RandomAccessFile(stateFile, "rw").use { file ->
        file.channel.lock().use {
            val stored = file.readLine()?.split('=', limit = 2)
            val previousBuild = if (stored?.getOrNull(0) == baseVersion) stored.getOrNull(1)?.toIntOrNull() ?: 0 else 0
            val buildNumber = previousBuild + 1
            require(buildNumber <= 65535) { "Local installer build numbers for $baseVersion are exhausted" }
            file.setLength(0)
            file.seek(0)
            file.writeBytes("$baseVersion=$buildNumber")
            file.fd.sync()
            buildNumber
        }
    }
}

val installerOutputDir = layout.buildDirectory.dir("dist").get().asFile
val installerBuildNumber = providers.gradleProperty("remotely.buildNumber")
    .orElse(providers.environmentVariable("GITHUB_RUN_NUMBER"))
    .orElse(providers.environmentVariable("CI_PIPELINE_IID"))
    .orElse(providers.environmentVariable("BUILD_NUMBER"))
val developerBuild = providers.gradleProperty("remotely.devBuild").map { value ->
    value.toBooleanStrictOrNull() ?: throw GradleException("remotely.devBuild must be true or false")
}.orElse(false)

val javaLauncher = extensions.getByType<JavaToolchainService>().launcherFor {
    languageVersion.set(JavaLanguageVersion.of(21))
}
val jpackageExecutable = javaLauncher.map {
    val executable = if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) "jpackage.exe" else "jpackage"
    File(it.metadata.installationPath.asFile, "bin/$executable").absolutePath
}

fun preparePackageInput(stagingDir: File, outputDir: File, artifactName: String) {
    outputDir.deleteRecursively()
    stagingDir.deleteRecursively()
    stagingDir.mkdirs()
    copy {
        from(tasks.named<Jar>("fatJar").flatMap { it.archiveFile })
        into(stagingDir)
        rename { packageJarName }
    }
    requireReleaseClasses(fileTree(stagingDir) { include("*.jar") }.files, artifactName)
}

tasks.register<Exec>("createInstaller") {
    dependsOn("fatJar")

    val stagingDir = layout.buildDirectory.dir("package-input/windows").get().asFile
    val iconPath = layout.projectDirectory.file("packaging/Remotely.ico").asFile.absolutePath

    doFirst {
        preparePackageInput(stagingDir, installerOutputDir, "Remotely Windows Installer")
        val isDeveloperBuild = developerBuild.get()
        val requestedBuildNumber = installerBuildNumber.orNull
        val buildNumber = if (!isDeveloperBuild) {
            null
        } else if (requestedBuildNumber != null) {
            requestedBuildNumber.toIntOrNull() ?: throw GradleException("Installer build number must be numeric")
        } else {
            nextLocalInstallerBuildNumber(cleanVersion)
        }
        val installerVersion = buildNumber?.let { developmentInstallerVersion(cleanVersion, it) } ?: cleanVersion
        val packageName = if (isDeveloperBuild) "Remotely Developer" else "Remotely"
        val upgradeArguments = if (isDeveloperBuild) arrayOf("--win-upgrade-uuid", developerWindowsUpgradeUuid) else emptyArray()

        commandLine(
            jpackageExecutable.get(),
            "--type", "exe",
            "--dest", installerOutputDir.absolutePath,
            "--input", stagingDir.absolutePath,
            "--name", packageName,
            "--main-jar", packageJarName,
            "--main-class", application.mainClass.get(),
            "--app-version", installerVersion,
            *upgradeArguments,
            "--icon", iconPath,
            "--jlink-options", "--strip-debug --no-man-pages --no-header-files",
            "--win-shortcut",
            "--win-menu",
            "--win-menu-group", "ReStudio",
            "--win-dir-chooser",
            "--java-options", "-Dfile.encoding=UTF-8",
            "--java-options", "-Xmx4G",
            "--java-options", "--enable-native-access=ALL-UNNAMED"
        )
    }
}

tasks.register<Exec>("createLinuxAppImage") {
    dependsOn("fatJar")

    val stagingDir = layout.buildDirectory.dir("package-input/linux").get().asFile
    val outputDir = layout.buildDirectory.dir("app-image").get().asFile
    val iconPath = layout.projectDirectory.file("packaging/Remotely.png").asFile.absolutePath

    doFirst {
        preparePackageInput(stagingDir, outputDir, "Remotely Linux AppImage")
    }

    commandLine(
        jpackageExecutable.get(),
        "--type", "app-image",
        "--dest", outputDir.absolutePath,
        "--input", stagingDir.absolutePath,
        "--name", "Remotely",
        "--main-jar", packageJarName,
        "--main-class", application.mainClass.get(),
        "--app-version", cleanVersion,
        "--icon", iconPath,
        "--jlink-options", "--strip-debug --no-man-pages --no-header-files",
        "--java-options", "-Dfile.encoding=UTF-8",
        "--java-options", "-Xmx4G",
        "--java-options", "--enable-native-access=ALL-UNNAMED"
    )
}

tasks.register<Exec>("createMacDmg") {
    dependsOn("fatJar")

    val stagingDir = layout.buildDirectory.dir("package-input/macos").get().asFile
    val outputDir = layout.buildDirectory.dir("dist-macos").get().asFile
    val iconPath = providers.gradleProperty("remotely.packageIcon").orElse(
        layout.projectDirectory.file("packaging/Remotely.icns").asFile.absolutePath
    )
    val macVersion = cleanVersion.split('.').take(3).joinToString(".")

    doFirst {
        preparePackageInput(stagingDir, outputDir, "Remotely macOS Disk Image")
    }

    commandLine(
        jpackageExecutable.get(),
        "--type", "dmg",
        "--dest", outputDir.absolutePath,
        "--input", stagingDir.absolutePath,
        "--name", "Remotely",
        "--main-jar", packageJarName,
        "--main-class", application.mainClass.get(),
        "--app-version", macVersion,
        "--icon", iconPath.get(),
        "--jlink-options", "--strip-debug --no-man-pages --no-header-files",
        "--mac-package-identifier", "net.restudiomc.remotely",
        "--java-options", "-Dfile.encoding=UTF-8",
        "--java-options", "-Xmx4G",
        "--java-options", "--enable-native-access=ALL-UNNAMED",
        "--java-options", "-XstartOnFirstThread"
    )
}
