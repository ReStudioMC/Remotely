package redxax.oxy.remotely.flow.ui.studio;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReSyncResourceCreatorAutomationTest {
    private static final Path SOURCE = Path.of(
        "src/main/java/redxax/oxy/remotely/flow/ui/studio/ReSyncResourceCreator.java");

    @Test
    void automationCreationOpensAStudioDesignerDocument() throws Exception {
        String source = Files.readString(SOURCE);
        String popup = method(source, "public static void showCreatePopup", "private static ReSyncValueTypeCatalog");

        assertTrue(popup.contains("AutomationDefinitionDraft.supports(type)"));
        assertTrue(popup.contains("studio.openDefinitionCreate(type, folder, onCreated)"));
        assertFalse(source.contains("class AutomationForm"));
        assertFalse(source.contains("class TargetDropDown"));
    }

    @Test
    void preparedAutomationIsRevalidatedAgainstItsExactTypeAuthorities() throws Exception {
        String source = Files.readString(SOURCE);
        String create = method(source, "public static Submission createPreparedAutomation", "private static Submission rejectedSubmission");

        assertTrue(create.contains("type.equals(prepared.type())"));
        assertTrue(create.contains("AutomationDefinitionDraft.prepare(type, id, prepared.document(), typeCatalog.dataTypes())"));
        assertTrue(create.contains("isCurrentTypeCatalog(serverId, typeCatalog)"));
        assertTrue(create.contains("manager.isAvailableScheduleTarget"));
        assertTrue(create.contains("manager.beginResourceCreation"));
        assertTrue(create.contains("exact.document().toString()"));
        assertTrue(create.contains("new Submission(admission.queued(), admission.message(), admission.durable())"));
    }

    @Test
    void legacyAutomationTemplateEntryAlsoUsesPreparedAdmission() throws Exception {
        String source = Files.readString(SOURCE);
        String create = method(source, "private static void create(String serverId", "private static String coreCreationFailure");

        assertTrue(create.contains("if (AutomationDefinitionDraft.supports(type))"));
        assertTrue(create.contains("createPreparedAutomation(serverId, type, id, folder, prepared, catalog, completion)"));
        assertTrue(create.indexOf("createPreparedAutomation") < create.indexOf("manager.beginResourceCreation"));
    }

    private static String method(String source, String start, String end) {
        int startIndex = source.indexOf(start);
        int endIndex = source.indexOf(end, startIndex + start.length());
        assertTrue(startIndex >= 0);
        assertTrue(endIndex > startIndex);
        return source.substring(startIndex, endIndex);
    }
}
