package redxax.oxy.remotely.network.config;

import org.junit.jupiter.api.Test;
import redxax.oxy.remotely.settings.server.BrowserSafeYaml;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NetworkConfigurationAdaptersTest {
    @Test
    void preparedReadersKeepTheirDocumentSnapshotAcrossEdits() {
        assertPrepared(new PropertiesConfigurationAdapter(), "name=first\nname=second\n", "name", "second", "name=third\n");
        assertPrepared(new TomlConfigurationAdapter(BrowserSafeYaml::parse),
                "[server]\nname = \"first\"\nname = \"second\"\n", "server.name", "\"first\"", "[server]\nname = \"third\"\n");
        assertPrepared(new YamlConfigurationAdapter(BrowserSafeYaml::parse),
                "server:\n  name: first\n  name: second\n", "server.name", "first", "server:\n  name: third\n");
    }

    private void assertPrepared(NetworkConfigurationAdapter adapter, String before, String key, String expected, String after) {
        NetworkConfigurationAdapter.Reader reader = adapter.prepare(before);
        assertTrue(reader.contains(key));
        assertEquals(expected, reader.read(key));
        assertEquals(adapter.contains(before, key), reader.contains(key));
        assertEquals(adapter.read(before, key), reader.read(key));
        assertFalse(reader.contains("missing"));
        assertEquals("", reader.read("missing"));

        NetworkConfigurationAdapter.Reader updated = adapter.prepare(after);
        assertEquals(expected, reader.read(key));
        assertEquals(adapter.read(after, key), updated.read(key));
        assertTrue(updated.contains(key));
    }

    @Test
    void preparedReadersPreserveSectionAndWildcardBoundaries() {
        TomlConfigurationAdapter toml = new TomlConfigurationAdapter(BrowserSafeYaml::parse);
        String tomlText = "[servers]\ntry = [\n[other]\nname = \"keep\"\n";
        NetworkConfigurationAdapter.Reader tomlReader = toml.prepare(tomlText);
        assertEquals(toml.read(tomlText, "servers.try"), tomlReader.read("servers.try"));
        assertEquals(toml.read(tomlText, "other.name"), tomlReader.read("other.name"));
        assertEquals(toml.contains(tomlText, "servers.*"), tomlReader.contains("servers.*"));

        YamlConfigurationAdapter yaml = new YamlConfigurationAdapter(BrowserSafeYaml::parse);
        String yamlText = "world-settings:\n  default:\n    verbose: false\n  world:\n    verbose: true\n";
        NetworkConfigurationAdapter.Reader yamlReader = yaml.prepare(yamlText);
        assertEquals(yaml.read(yamlText, "world-settings.*"), yamlReader.read("world-settings.*"));
        assertEquals(yaml.contains(yamlText, "world-settings.*"), yamlReader.contains("world-settings.*"));
        assertEquals(yaml.read(yamlText, "world-settings.default.verbose"), yamlReader.read("world-settings.default.verbose"));
    }

    @Test
    void propertiesPreservesUnmanagedLines() {
        PropertiesConfigurationAdapter adapter = new PropertiesConfigurationAdapter();
        String source = "# Server\r\nonline-mode=true\r\nmotd=Keep Me\r\n";

        String updated = adapter.apply(source, "online-mode", "false");

        assertEquals("false", adapter.read(updated, "online-mode"));
        assertTrue(updated.contains("motd=Keep Me"));
        assertTrue(updated.contains("\r\n"));
        assertTrue(adapter.remove(updated, "online-mode").contains("motd=Keep Me"));
    }

    @Test
    void propertiesPreservesSeparatorsSpacingAndTrailingWhitespace() {
        PropertiesConfigurationAdapter adapter = new PropertiesConfigurationAdapter();
        String source = "  online-mode  :   true   \r\nserver-port\t 25565\r\n";

        String updated = adapter.apply(source, "online-mode", "false");
        updated = adapter.apply(updated, "server-port", "25566");

        assertEquals("  online-mode  :   false   \r\nserver-port\t 25566\r\n", updated);
    }

    @Test
    void propertiesTreatsInlineCommentCharactersAsValueText() {
        PropertiesConfigurationAdapter adapter = new PropertiesConfigurationAdapter();
        String source = "# motd=ignored\n! motd=ignored\nmotd=Welcome # players!\nmarker: before!after\n";

        assertEquals("Welcome # players!", adapter.read(source, "motd"));
        assertEquals("before!after", adapter.read(source, "marker"));

        String updated = adapter.apply(source, "motd", "Welcome # admins!");

        assertEquals("# motd=ignored\n! motd=ignored\nmotd=Welcome # admins!\nmarker: before!after\n", updated);
    }

    @Test
    void propertiesSupportsWhitespaceSeparatorsAndBareKeys() {
        PropertiesConfigurationAdapter adapter = new PropertiesConfigurationAdapter();
        String source = "level-name world\nallow-flight\n";

        assertEquals("world", adapter.read(source, "level-name"));
        assertTrue(adapter.contains(source, "allow-flight"));

        String updated = adapter.apply(source, "level-name", "survival");
        updated = adapter.apply(updated, "allow-flight", "true");

        assertEquals("level-name survival\nallow-flight=true\n", updated);
    }

    @Test
    void propertiesReplacesContinuedValuesWithoutLeavingOldContinuationLines() {
        PropertiesConfigurationAdapter adapter = new PropertiesConfigurationAdapter();
        String source = "generator-settings={\\\n  \"seed\": 1}\nlevel-name=world\n";

        assertEquals("{\"seed\": 1}", adapter.read(source, "generator-settings"));

        String updated = adapter.apply(source, "generator-settings", "{}");

        assertEquals("generator-settings={}\nlevel-name=world\n", updated);
    }

    @Test
    void tomlUpdatesSectionsAndQuotesForcedHosts() {
        TomlConfigurationAdapter adapter = new TomlConfigurationAdapter(BrowserSafeYaml::parse);
        String source = "bind = \"0.0.0.0:25565\"\n\n[servers]\nlobby = \"127.0.0.1:25566\"\ntry = [\"lobby\"]\n\n[advanced]\ncompression-threshold = 256\n";

        String updated = adapter.apply(source, "servers.lobby", "\"127.0.0.1:25570\"");
        updated = adapter.apply(updated, "forced-hosts.play.example.com", "[\"lobby\"]");

        assertEquals("\"127.0.0.1:25570\"", adapter.read(updated, "servers.lobby"));
        assertEquals("[\"lobby\"]", adapter.read(updated, "forced-hosts.play.example.com"));
        assertTrue(updated.contains("compression-threshold = 256"));
        assertTrue(updated.contains("\"play.example.com\" = [\"lobby\"]"));
        assertEquals("", adapter.read(adapter.remove(updated, "servers.lobby"), "servers.lobby"));
    }

    @Test
    void tomlReadsQuotedHashesAndPreservesTrailingComments() {
        TomlConfigurationAdapter adapter = new TomlConfigurationAdapter(BrowserSafeYaml::parse);
        String source = """
                [servers]
                lobby = "127.0.0.1:25566 # internal" # Keep lobby route
                try = ["lobby # fallback"] # Keep try route

                [forced-hosts]
                "play.example.com" = ["lobby # route"] # Keep forced host
                """;

        assertEquals("\"127.0.0.1:25566 # internal\"", adapter.read(source, "servers.lobby"));
        assertEquals("[\"lobby # fallback\"]", adapter.read(source, "servers.try"));
        assertEquals("[\"lobby # route\"]", adapter.read(source, "forced-hosts.play.example.com"));

        String updated = adapter.apply(source, "servers.lobby", "\"127.0.0.1:25570 # updated\"");
        updated = adapter.apply(updated, "forced-hosts.play.example.com", "[\"survival # route\"]");

        assertEquals("\"127.0.0.1:25570 # updated\"", adapter.read(updated, "servers.lobby"));
        assertEquals("[\"survival # route\"]", adapter.read(updated, "forced-hosts.play.example.com"));
        assertTrue(updated.contains("lobby = \"127.0.0.1:25570 # updated\" # Keep lobby route"));
        assertTrue(updated.contains("\"play.example.com\" = [\"survival # route\"] # Keep forced host"));
    }

    @Test
    void tomlReplacesDynamicSectionsWithQuotedHashes() {
        TomlConfigurationAdapter adapter = new TomlConfigurationAdapter(BrowserSafeYaml::parse);
        String source = """
                [servers]
                lobby = "127.0.0.1:25566 # internal" # Keep lobby route
                try = ["lobby"] # Remove old try route
                """;

        String updated = adapter.apply(source, "servers.*", "{lobby: \"127.0.0.1:25570 # updated\", fallback: [\"lobby # fallback\"]}");

        assertEquals("{lobby: \"127.0.0.1:25570 # updated\", fallback: [\"lobby # fallback\"]}", adapter.read(updated, "servers.*"));
        assertEquals("\"127.0.0.1:25570 # updated\"", adapter.read(updated, "servers.lobby"));
        assertEquals("[\"lobby # fallback\"]", adapter.read(updated, "servers.fallback"));
        assertTrue(updated.contains("lobby = \"127.0.0.1:25570 # updated\" # Keep lobby route"));
        assertFalse(updated.contains("Remove old try route"));
    }

    @Test
    void tomlReplacesAndRemovesWholeMultilineArrays() {
        TomlConfigurationAdapter adapter = new TomlConfigurationAdapter(BrowserSafeYaml::parse);
        String source = """
                [servers]
                lobby = "127.0.0.1:30066"
                try = [
                    "lobby"
                ]

                [forced-hosts]
                "lobby.example.com" = [
                    "lobby"
                ]
                """;

        String updated = adapter.apply(source, "servers.try", "[\"survival\"]");
        updated = adapter.remove(updated, "forced-hosts.lobby.example.com");

        assertEquals("[\"survival\"]", adapter.read(updated, "servers.try"));
        assertEquals("", adapter.read(updated, "forced-hosts.lobby.example.com"));
        assertTrue(updated.contains("lobby = \"127.0.0.1:30066\""));
        assertFalse(updated.contains("    \"lobby\""));
    }

    @Test
    void tomlClearsManagedSectionsWithoutLeavingMultilineValues() {
        TomlConfigurationAdapter adapter = new TomlConfigurationAdapter(BrowserSafeYaml::parse);
        String source = """
                [servers]
                lobby = "127.0.0.1:30066"
                try = [
                    "lobby"
                ]

                [advanced]
                compression-threshold = 256
                """;

        String updated = adapter.remove(source, "servers.*");

        assertTrue(adapter.contains(source, "servers.*"));
        assertFalse(adapter.contains(updated, "servers.*"));
        assertTrue(updated.contains("compression-threshold = 256"));
        assertFalse(updated.contains("127.0.0.1:30066"));
        assertFalse(updated.contains("    \"lobby\""));
    }

    @Test
    void tomlCreatesExplicitEmptySectionWhenClearingMissingDefaults() {
        TomlConfigurationAdapter adapter = new TomlConfigurationAdapter(BrowserSafeYaml::parse);
        String source = "config-version = \"2.8\"\n\n[servers]\nlobby = \"127.0.0.1:25566\"\n";

        String updated = adapter.remove(source, "forced-hosts.*");

        assertTrue(updated.contains("[forced-hosts]"));
        assertFalse(adapter.contains(updated, "forced-hosts.*"));
        assertEquals(updated, adapter.remove(updated, "forced-hosts.*"));
    }

    @Test
    void tomlReadsAndReplacesWholeSectionsAsStructuredMaps() {
        TomlConfigurationAdapter adapter = new TomlConfigurationAdapter(BrowserSafeYaml::parse);
        String source = "# Proxy\n[servers]\nlobby = \"127.0.0.1:25566\"\ntry = [\"lobby\"]\n\n[advanced]\ncompression-threshold = 256\n";

        assertEquals("{lobby: \"127.0.0.1:25566\", try: [\"lobby\"]}", adapter.read(source, "servers.*"));
        assertTrue(adapter.contains(source, "servers.*"));

        String updated = adapter.apply(source, "servers.*", "{lobby: \"127.0.0.1:25570\", fallback: [\"lobby\"]}");

        assertEquals("\"127.0.0.1:25570\"", adapter.read(updated, "servers.lobby"));
        assertEquals("[\"lobby\"]", adapter.read(updated, "servers.fallback"));
        assertFalse(adapter.contains(updated, "servers.try"));
        assertTrue(updated.contains("# Proxy"));
        assertTrue(updated.contains("compression-threshold = 256"));

        String removed = adapter.remove(updated, "servers.*");
        assertFalse(adapter.contains(removed, "servers.*"));
        assertTrue(removed.contains("[servers]"));
        assertTrue(removed.contains("[advanced]"));
    }

    @Test
    void yamlUpdatesNestedValuesWithoutRewritingDocument() {
        YamlConfigurationAdapter adapter = new YamlConfigurationAdapter(BrowserSafeYaml::parse);
        String source = "settings:\n  bungeecord: true # Keep Note\nworld-settings:\n  default:\n    verbose: false\n";

        String updated = adapter.apply(source, "settings.bungeecord", "false");
        updated = adapter.apply(updated, "proxies.velocity.enabled", "true");
        updated = adapter.apply(updated, "proxies.velocity.secret", "a-secret");

        assertEquals("false", adapter.read(updated, "settings.bungeecord"));
        assertEquals("true", adapter.read(updated, "proxies.velocity.enabled"));
        assertEquals("a-secret", adapter.read(updated, "proxies.velocity.secret"));
        assertTrue(updated.contains("# Keep Note"));
        assertTrue(updated.contains("world-settings:\n  default:\n    verbose: false"));
        assertEquals("", adapter.read(adapter.remove(updated, "proxies.velocity.secret"), "proxies.velocity.secret"));
    }

    @Test
    void yamlSupportsEscapedDottedKeysAndDynamicWorldSections() {
        YamlConfigurationAdapter adapter = new YamlConfigurationAdapter(BrowserSafeYaml::parse);
        String source = "world-settings:\n  default:\n    keep: true # Keep default\n  world.one:\n    value: 1\n  world-two:\n    value: 2\nserver-settings:\n  keep: true\n";

        assertEquals("true", adapter.read(source, "world-settings.default.keep"));
        assertEquals("1", adapter.read(source, "world-settings.world\\.one.value"));
        assertTrue(adapter.contains(source, "world-settings.*"));

        String updated = adapter.apply(source, "world-settings.*", "{world.one: {value: 3}, world-three: {value: 4}}");

        assertEquals("true", adapter.read(updated, "world-settings.default.keep"));
        assertEquals("3", adapter.read(updated, "world-settings.world\\.one.value"));
        assertEquals("4", adapter.read(updated, "world-settings.world-three.value"));
        assertFalse(adapter.contains(updated, "world-settings.missing.value"));
        assertTrue(updated.contains("keep: true # Keep default"));
        assertEquals("true", adapter.read(updated, "server-settings.keep"), updated);

        String removed = adapter.remove(updated, "world-settings.*");
        assertEquals("true", adapter.read(removed, "world-settings.default.keep"));
        assertFalse(adapter.contains(removed, "world-settings.*"));
    }

    @Test
    void yamlTreatsNamespacedKeysAsWholeKeysInsideDynamicMaps() {
        YamlConfigurationAdapter adapter = new YamlConfigurationAdapter(BrowserSafeYaml::parse);
        String source = "packet-limiter:\n  overrides:\n    minecraft:place_recipe:\n      action: DROP\n      interval: 4.0\n    'minecraft:elytra':\n      action: KICK\n      interval: 8.0\n";

        assertEquals("DROP", adapter.read(source, "packet-limiter.overrides.minecraft:place_recipe.action"));
        assertEquals("KICK", adapter.read(source, "packet-limiter.overrides.minecraft:elytra.action"));
        assertTrue(adapter.contains(source, "packet-limiter.overrides.*"));
        assertTrue(adapter.read(source, "packet-limiter.overrides.*").contains("minecraft:place_recipe:"));
        assertTrue(adapter.read(source, "packet-limiter.overrides.*").contains("'minecraft:elytra':"));

        String updated = adapter.apply(source, "packet-limiter.overrides.minecraft:place_recipe.interval", "5.0");
        updated = adapter.apply(updated, "packet-limiter.overrides.minecraft:elytra.interval", "9.0");

        assertEquals("5.0", adapter.read(updated, "packet-limiter.overrides.minecraft:place_recipe.interval"));
        assertEquals("9.0", adapter.read(updated, "packet-limiter.overrides.minecraft:elytra.interval"));
        assertTrue(updated.contains("minecraft:place_recipe:\n      action: DROP"));
        assertTrue(updated.contains("'minecraft:elytra':\n      action: KICK"));
    }
}
