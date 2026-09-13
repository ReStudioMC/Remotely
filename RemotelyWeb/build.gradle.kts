import org.gradle.api.tasks.Sync
import org.teavm.gradle.api.SourceFilePolicy

import java.security.MessageDigest
import java.util.ArrayDeque
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

private data class BrowserClassConstant(val nameIndex: Int)

private data class BrowserNameAndTypeConstant(val nameIndex: Int, val descriptorIndex: Int)

private data class BrowserMethodConstant(val classIndex: Int, val nameAndTypeIndex: Int)

private fun hasBrowserMethodReference(bytes: ByteArray, owner: String, names: Set<String>): Boolean {
    fun u1(index: Int): Int = bytes[index].toInt() and 0xFF
    fun u2(index: Int): Int = (u1(index) shl 8) or u1(index + 1)

    if (bytes.size < 10 || u1(0) != 0xCA || u1(1) != 0xFE || u1(2) != 0xBA || u1(3) != 0xBE) return false
    val constants = arrayOfNulls<Any>(u2(8))
    var offset = 10
    var index = 1
    while (index < constants.size) {
        when (u1(offset++)) {
            1 -> {
                val length = u2(offset)
                offset += 2
                constants[index] = String(bytes, offset, length, Charsets.UTF_8)
                offset += length
            }
            3, 4 -> offset += 4
            5, 6 -> {
                offset += 8
                index++
            }
            7 -> {
                constants[index] = BrowserClassConstant(u2(offset))
                offset += 2
            }
            8, 16, 19, 20 -> offset += 2
            9 -> offset += 4
            10, 11 -> {
                constants[index] = BrowserMethodConstant(u2(offset), u2(offset + 2))
                offset += 4
            }
            12 -> {
                constants[index] = BrowserNameAndTypeConstant(u2(offset), u2(offset + 2))
                offset += 4
            }
            15 -> offset += 3
            17, 18 -> offset += 4
            else -> error("Unsupported browser class constant pool tag: ${u1(offset - 1)}")
        }
        index++
    }
    return constants.filterIsInstance<BrowserMethodConstant>().any { method ->
        val classConstant = constants.getOrNull(method.classIndex) as? BrowserClassConstant ?: return@any false
        val nameAndType = constants.getOrNull(method.nameAndTypeIndex) as? BrowserNameAndTypeConstant ?: return@any false
        val methodOwner = constants.getOrNull(classConstant.nameIndex) as? String ?: return@any false
        val methodName = constants.getOrNull(nameAndType.nameIndex) as? String ?: return@any false
        methodOwner == owner && methodName in names
    }
}

private fun browserMetadataEdges(bytes: ByteArray): Set<String> {
    val edges = linkedSetOf<String>()
    if (hasBrowserMethodReference(bytes, "java/lang/Object", setOf("getClass"))) {
        edges += "java/lang/Object.getClass"
    }
    if (hasBrowserMethodReference(bytes, "java/lang/Class", setOf(
            "forName", "getSimpleName", "getName", "getCanonicalName", "getTypeName", "getDeclaringClass", "getEnumConstants",
            "getComponentType", "getSuperclass", "getInterfaces", "getMethods", "getMethod", "getFields",
            "getField", "getConstructors", "getConstructor", "getDeclaredConstructors", "getDeclaredConstructor",
            "getDeclaredMethods", "getDeclaredMethod", "getDeclaredFields", "getDeclaredField", "newInstance", "cast",
            "isEnum", "isInstance", "isAssignableFrom", "asSubclass", "getModifiers", "getPackage", "getPackageName"))) {
        edges += "java/lang/Class metadata/reflection"
    }
    return edges
}

plugins {
    java
    application
    id("org.teavm") version "0.15.0"
}

group = "dev.restudio"
version = rootProject.version
val useReStudioSourceDependencies = rootProject.extra["reStudioSourceDependencies"] as Boolean

repositories {
    mavenLocal {
        content {
            includeGroup("dev.restudio")
            includeGroup("restudio.resync")
        }
    }
    mavenCentral()
}

dependencies {
    implementation(project(path = ":", configuration = "browserElements"))
    compileOnly("org.teavm:teavm-core:0.15.0")
    implementation("org.teavm:teavm-jso:0.15.0")
    implementation("org.teavm:teavm-jso-apis:0.15.0")
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    teavm(teavm.libs.jso)
    teavm(teavm.libs.jsoApis)
}

if (useReStudioSourceDependencies) {
    dependencies {
        implementation(files(
            "../../ReScreen/build/libs/ReScreen-1.0-browser.jar",
            "../../Rebase/build/libs/Rebase-1.0-SNAPSHOT-browser.jar",
            "../../ReSync/ReSyncCore/build/libs/ReSyncCore-1.3.0-browser.jar"
        ))
    }
    tasks.named("compileJava") {
        dependsOn(
            gradle.includedBuild("ReScreen").task(":browserJar"),
            gradle.includedBuild("Rebase").task(":browserJar"),
            gradle.includedBuild("ReSync").task(":ReSyncCore:browserJar")
        )
    }
} else {
    dependencies {
        implementation("dev.restudio:rescreen:1.0:browser") { isTransitive = false }
        implementation("dev.restudio:rebase:1.0-SNAPSHOT:browser") { isTransitive = false }
        implementation("restudio.resync:ReSyncCore:1.3.0:browser") { isTransitive = false }
    }
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

application {
    mainClass.set("redxax.oxy.remotely.web.RemotelyBrowserMain")
}

val canonicalBrowserClasses = linkedSetOf(
    "redxax.oxy.remotely.ui.server.ServerManagerScreen",
    "redxax.oxy.remotely.ui.server.ServerDetailsScreen",
    "redxax.oxy.remotely.ui.server.NetworkOverviewScreen",
    "redxax.oxy.remotely.ui.server.ServerTerminal",
    "redxax.oxy.remotely.ui.server.ServerConfigurationScreen",
    "redxax.oxy.remotely.flow.ui.FlowEditorScreen",
    "redxax.oxy.remotely.flow.ui.GraphEditorScreen",
    "redxax.oxy.remotely.flow.ui.studio.StudioScreen",
    "redxax.oxy.remotely.flow.ui.marketplace.ReSyncMarketplaceScreen",
    "redxax.oxy.remotely.worldgen.ui.WorldGenEditorScreen",
    "restudio.rebase.ui.screens.explorer.FileExplorerScreen",
    "restudio.rebase.ui.screens.editor.FileEditorScreen",
    "restudio.rebase.ui.widgets.TerminalWidget",
    "restudio.rebase.ui.screens.resources.ResourceBrowserScreen",
    "restudio.rebase.ui.screens.resources.ResourceOverviewScreen",
    "restudio.rebase.ui.screens.resources.ResourceOverviewFrame",
    "restudio.rebase.ui.screens.marketplace.MarketplaceBrowserScreen",
    "restudio.rebase.ui.screens.marketplace.MarketplaceDetailsScreen",
    "restudio.rebase.ui.screens.marketplace.MarketplacePublishScreen",
    "restudio.rebase.ui.screens.marketplace.MarketplaceVersionPublishPopup",
    "restudio.rebase.ui.screens.marketplace.MarketplaceReviewScreen",
    "restudio.rebase.ui.worldmap.WorldMapScreen",
    "restudio.rescreen.ui.settings.SettingsScreen",
    "restudio.rebase.ui.screens.auth.ReStudioLoginScreen",
    "restudio.rebase.ui.screens.auth.ReStudioProfileSetupPopup",
    "restudio.rebase.ui.screens.notification.InboxScreen",
    "restudio.rebase.ui.screens.feedback.FeedbackBrowserScreen",
    "restudio.rebase.ui.screens.feedback.FeedbackDetailsScreen",
    "restudio.rebase.ui.screens.feedback.CreateFeedbackPopup"
)

val verifyBrowserGraph by tasks.registering {
    dependsOn(tasks.named("classes"))
    doLast {
        val forbiddenDependencies = listOf("jediterm", "pty4j", "gradle-tooling", "lwjgl", "jna", "sshj", "java-websocket")
        val forbiddenSymbols = listOf(
            "java/awt/",
            "javax/sound/",
            "java/net/http/",
            "java/nio/file/",
            "java/io/File",
            "java/io/RandomAccessFile",
            "java/lang/Process",
            "java/util/concurrent/",
            "java/util/Timer",
            "java/lang/foreign/",
            "java/lang/reflect/",
            "java/util/ServiceLoader",
            "sun/misc/Unsafe",
            "platform/jvm/",
            "/platform/desktop/",
            "org/lwjgl/",
            "com/sun/jna/",
            "com/pty4j/",
            "com/jediterm/",
            "org/gradle/",
            "net/schmizz/sshj/"
        )
        val classpath = configurations.runtimeClasspath.get().files
        val leakedDependencies = classpath.filter { file -> forbiddenDependencies.any { file.name.lowercase().contains(it) } }
        require(leakedDependencies.isEmpty()) { "Browser Classpath Contains Desktop Dependencies: ${leakedDependencies.joinToString { it.name }}" }
        val browserArtifacts = classpath.filter { it.name.endsWith("-browser.jar") }
        require(browserArtifacts.size == 4) { "Browser Classpath Must Contain Only The Remotely, ReScreen, Rebase, And ReSync Core Browser Variants" }
        val requiredClasses = setOf(
            *canonicalBrowserClasses.map { it.replace('.', '/') }.toTypedArray(),
            "redxax/oxy/remotely/ui/server/ServerManagerScreen",
            "redxax/oxy/remotely/ui/server/ServerDetailsScreen",
            "redxax/oxy/remotely/ui/server/ServerTerminal",
            "redxax/oxy/remotely/data/flow/FlowManager",
            "redxax/oxy/remotely/flow/ui/GraphEditorScreen",
            "redxax/oxy/remotely/flow/ui/studio/StudioScreen",
            "redxax/oxy/remotely/flow/ui/marketplace/ReSyncMarketplaceScreen",
            "redxax/oxy/remotely/packcontent/NexoGlyphLoader",
            "redxax/oxy/remotely/packcontent/NexoGlyphPreviewAccess",
            "redxax/oxy/remotely/worldgen/WorldGenManager",
            "restudio/rebase/ui/screens/explorer/FileExplorerScreen",
            "restudio/rebase/ui/screens/editor/FileEditorScreen",
            "restudio/rebase/ui/widgets/TerminalWidget",
            "restudio/rebase/ui/worldmap/WorldMapScreen",
            "restudio/rebase/backend/DeveloperCapabilityProvider",
            "restudio/rebase/restudio/marketplace/MarketplaceDetailsProvider",
            "restudio/rebase/ui/screens/marketplace/MarketplaceDetailsScreen",
            "restudio/rebase/ui/screens/marketplace/MarketplaceVersionPublishPopup",
            "restudio/rebase/ui/screens/marketplace/MarketplaceReviewScreen",
            "restudio/rescreen/ui/rescreen/ReScreen",
            "restudio/rescreen/ui/widgets/CompactBindingWidget",
            "restudio/rescreen/game/MinecraftGameEntities"
        )
        val leaks = mutableListOf<String>()
        val classBytes = linkedMapOf<String, ByteArray>()
        browserArtifacts.filter(File::isFile).forEach { archive ->
            ZipFile(archive).use { zip ->
                zip.entries().asSequence().filter { entry: ZipEntry -> !entry.isDirectory && entry.name.endsWith(".class") }.forEach { entry: ZipEntry ->
                    val bytes = zip.getInputStream(entry).readBytes()
                    classBytes[entry.name.removeSuffix(".class")] = bytes
                    val symbols = bytes.toString(Charsets.ISO_8859_1)
                    forbiddenSymbols.filter { symbol -> symbols.contains(symbol) }.forEach { symbol -> leaks += "${archive.name}:${entry.name}:$symbol" }
                    if (symbols.contains("\u0000\u0010java/lang/Thread")) leaks += "${archive.name}:${entry.name}:java/lang/Thread"
                    if (hasBrowserMethodReference(bytes, "java/lang/Runtime", setOf("exec"))) leaks += "${archive.name}:${entry.name}:java/lang/Runtime.exec"
                    if (hasBrowserMethodReference(bytes, "java/lang/Class", setOf("forName"))) leaks += "${archive.name}:${entry.name}:java/lang/Class.forName"
                    browserMetadataEdges(bytes).forEach { edge -> leaks += "${archive.name}:${entry.name}:$edge" }
                    if (hasBrowserMethodReference(bytes, "java/lang/System", setOf("load", "loadLibrary"))) {
                        leaks += "${archive.name}:${entry.name}:java/lang/System.load"
                    }
                }
            }
        }
        val missingRequired = requiredClasses.filterNot(classBytes::containsKey)
        require(missingRequired.isEmpty()) { "Browser Variants Are Missing Required Shared Classes:\n${missingRequired.joinToString("\n")}" }
        val forbiddenClasses = classBytes.keys.filter {
            it.substringAfterLast('/').substringBefore('$') in setOf("DesktopSoundHandler", "HttpUtils")
        }
        require(forbiddenClasses.isEmpty()) { "Browser Variants Contain Desktop Or JVM Utility Classes:\n${forbiddenClasses.joinToString("\n")}" }
        require(!file("src/main/java/redxax/oxy/remotely/web/BrowserServerManagerScreen.java").exists()) {
            "The Browser Must Use The Shared ServerManagerScreen"
        }
        val serverManagerSource = file("../src/main/java/redxax/oxy/remotely/ui/server/ServerManagerScreen.java").readText()
        require(serverManagerSource.contains("serverScreenHost") && serverManagerSource.contains("openModpackBrowser")) {
            "The Canonical ServerManagerScreen Must Route Resource Browsing Through The Host Capability"
        }
        val browserHostSource = file("src/main/java/redxax/oxy/remotely/web/platform/BrowserServerScreenHost.java").readText()
        require(browserHostSource.contains("new ResourceBrowserScreen") && browserHostSource.contains("ResourceMarketplaceProviderAdapter")
                && browserHostSource.contains("ResourceProviderCatalog") && browserHostSource.contains("createAsync")) {
            "The Browser Host Must Open The Canonical Resource Browser Through The Shared Provider"
        }
        require(!file("src/main/java/redxax/oxy/remotely/web/platform/BrowserResourceMarketplaceProvider.java").exists()) {
            "The Browser Must Not Contain A Duplicate Resource Marketplace Provider"
        }
        require(!file("src/main/java/redxax/oxy/remotely/web/platform/BrowserGlyphPreviewAccess.java").exists()) {
            "The Browser Must Use The Shared Nexo Glyph Preview Access"
        }
        val roots = mutableSetOf<String>()
        sourceSets.main.get().output.classesDirs.files.filter(File::isDirectory).forEach { classDir ->
            classDir.walkTopDown().filter { it.isFile && it.extension == "class" }.forEach { classFile ->
                val bytes = classFile.readBytes()
                val className = classFile.relativeTo(classDir).invariantSeparatorsPath.removeSuffix(".class")
                roots += className
                classBytes[className] = bytes
                val symbols = bytes.toString(Charsets.ISO_8859_1)
                forbiddenSymbols.filter { symbol -> symbols.contains(symbol) }.forEach { symbol ->
                    leaks += "RemotelyWeb:${classFile.relativeTo(classDir).invariantSeparatorsPath}:$symbol"
                }
                if (symbols.contains("\u0000\u0010java/lang/Thread")) {
                    leaks += "RemotelyWeb:${classFile.relativeTo(classDir).invariantSeparatorsPath}:java/lang/Thread"
                }
                if (hasBrowserMethodReference(bytes, "java/lang/Runtime", setOf("exec"))) {
                    leaks += "RemotelyWeb:${classFile.relativeTo(classDir).invariantSeparatorsPath}:java/lang/Runtime.exec"
                }
                if (hasBrowserMethodReference(bytes, "java/lang/Class", setOf("forName"))) {
                    leaks += "RemotelyWeb:${classFile.relativeTo(classDir).invariantSeparatorsPath}:java/lang/Class.forName"
                }
                browserMetadataEdges(bytes).forEach { edge -> leaks += "RemotelyWeb:${classFile.relativeTo(classDir).invariantSeparatorsPath}:$edge" }
                if (hasBrowserMethodReference(bytes, "java/lang/System", setOf("load", "loadLibrary"))) {
                    leaks += "RemotelyWeb:${classFile.relativeTo(classDir).invariantSeparatorsPath}:java/lang/System.load"
                }
            }
        }
        require(leaks.isEmpty()) { "Browser Reachability Contains Desktop Symbols:\n${leaks.joinToString("\n")}" }
        val internalReference = Regex("(?:L|\\[L)?((?:restudio/rebase|restudio/rescreen|restudio/resync|redxax/oxy/remotely)/[A-Za-z0-9_$/]+)")
        val artifactMissing = classBytes.entries.flatMap { (className, bytes) ->
            internalReference.findAll(bytes.toString(Charsets.ISO_8859_1)).map { it.groupValues[1] }.distinct()
                .filterNot(classBytes::containsKey).map { reference -> "$className -> $reference" }.toList()
        }.toSortedSet()
        require(artifactMissing.isEmpty()) {
            "Browser Artifacts Have Missing Internal Classes:\n${artifactMissing.joinToString("\n")}"
        }
        val missing = mutableSetOf<String>()
        val visited = mutableSetOf<String>()
        val pending = ArrayDeque<String>()
        (roots + requiredClasses).forEach(pending::addLast)
        while (pending.isNotEmpty()) {
            val className = pending.removeFirst()
            if (!visited.add(className)) continue
            val bytes = classBytes[className] ?: continue
            val symbols = bytes.toString(Charsets.ISO_8859_1)
            internalReference.findAll(symbols).map { it.groupValues[1] }.forEach { reference ->
                if (classBytes.containsKey(reference)) {
                    if (!visited.contains(reference)) pending.addLast(reference)
                } else {
                    missing += "$className -> $reference"
                }
            }
        }
        require(missing.isEmpty()) { "Browser Transitive Graph Has Missing Internal Classes:\n${missing.sorted().joinToString("\n")}" }
    }
}

tasks.named("test") {
    dependsOn(verifyBrowserGraph)
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}

tasks.named("generateJavaScript") {
    dependsOn(verifyBrowserGraph)
}

teavm {
    all {
        mainClass = "redxax.oxy.remotely.web.RemotelyBrowserMain"
        preservedClasses = canonicalBrowserClasses.toList()
    }
    js {
        targetFileName = "remotely-browser.js"
        sourceMap = true
        sourceFilePolicy = SourceFilePolicy.DO_NOTHING
        obfuscated = true
        relativePathInOutputDir = "js"
        devServer {
            port = 9093
        }
    }
}

tasks.register<Sync>("browserDist") {
    val distribution = layout.buildDirectory.dir("generated/teavm/remotely")
    dependsOn(tasks.named("generateJavaScript"), verifyBrowserGraph)
    from(layout.projectDirectory.dir("src/main/resources"))
    from(layout.buildDirectory.dir("generated/teavm/js")) {
        into("js")
    }
    from(layout.projectDirectory.dir("../../ReScreen/src/main/java")) {
        into("js/src")
    }
    from(layout.projectDirectory.dir("../../Rebase/src/main/java")) {
        into("js/src")
    }
    from(layout.projectDirectory.dir("../../ReSync/ReSyncCore/src/main/java")) {
        into("js/src")
    }
    from(layout.projectDirectory.dir("../../ReScreen/src/main/resources/assets")) {
        into("assets")
    }
    from(layout.projectDirectory.dir("../src/main/resources/assets")) {
        into("assets")
    }
    from(layout.projectDirectory.dir("../../Rebase/src/main/resources/assets")) {
        into("assets")
    }
    into(distribution)
    doLast {
        val root = distribution.get().asFile
        val bundle = root.resolve("js/remotely-browser.js")
        val index = root.resolve("index.html")
        require(bundle.isFile && bundle.length() > 0) { "Remotely Web browser bundle is missing or empty: $bundle" }
        require(index.isFile) { "Remotely Web index is missing: $index" }

        val buildId = MessageDigest.getInstance("SHA-256").digest(bundle.readBytes()).joinToString("") { "%02x".format(it.toInt() and 0xff) }
        val markerPattern = Regex("""(<meta\s+name="remotely-build-id"\s+content=")[^"]*(">)""")
        val indexHtml = index.readText()
        require(markerPattern.containsMatchIn(indexHtml)) { "Remotely Web build marker meta tag is missing: $index" }

        root.resolve("remotely-web-build-id.txt").writeText("$buildId\n")
        index.writeText(markerPattern.replace(indexHtml) { match -> "${match.groupValues[1]}$buildId${match.groupValues[2]}" })
    }
}
