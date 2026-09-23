package redxax.oxy.remotely.flow.ui.studio;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import redxax.oxy.remotely.data.flow.ReSyncCollaborationClient;
import redxax.oxy.remotely.ui.collaboration.CollaborationVisuals;
import restudio.rebase.backend.RemoteFileSystemProvider;
import restudio.rebase.backend.RemotePath;
import restudio.rebase.ui.screens.editor.WorkspaceTreeExplorer;
import restudio.rebase.ui.widgets.FileEntryWidget;
import restudio.rescreen.theme.ThemeManager;
import restudio.rescreen.ui.rescreen.Container;
import restudio.rescreen.ui.core.WidgetComposite;
import restudio.rescreen.ui.widgets.ImageWidget;
import restudio.rescreen.util.Identifier;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReSyncContentBrowserCollaborationTest {
    @BeforeEach
    void initializeTheme() {
        ThemeManager.initBrowserDefaults();
    }

    @Test
    void studioTickDrivesItsContentBrowser() {
        StudioScreen screen = new StudioScreen() { };
        int[] ticks = {0};
        ReSyncContentBrowserWidget browser = new ReSyncContentBrowserWidget(screen, 0, 0, 190, 600) {
            @Override
            public void tick() {
                ticks[0]++;
            }
        };
        screen.studioContentBrowser = browser;
        try {
            screen.tick();
            assertEquals(1, ticks[0]);
        } finally {
            browser.dispose();
        }
    }

    @Test
    void existingRowsFollowResourceColorAvatarAndDeparture() throws Exception {
        StudioScreen screen = new StudioScreen() { };
        screen.resize(1280, 720);
        ReSyncContentBrowserWidget browser = new ReSyncContentBrowserWidget(screen, 0, 0, 190, 600);
        try {
            RemotePath root = RemotePath.of("ReSync");
            var provider = ReSyncContentBrowserWidget.prepareTreeProvider(root, List.of(), List.of(
                resource("A"), resource("B")));
            field("treeProvider").set(browser, provider);
            Container container = (Container) field("treeContainer").get(browser);
            FileEntryWidget first = row(browser, provider, container, root.resolve("flow/A"), "A");
            FileEntryWidget second = row(browser, provider, container, root.resolve("flow/B"), "B");
            first.setSelected(true);
            Identifier originalAvatar = Identifier.icon("steve.png");
            browser.updateCollaboration(List.of(editor("A", 0xFF336699, originalAvatar)));

            assertTrue(highlighted(first));
            assertFalse(highlighted(second));
            assertEquals(originalAvatar, avatar(first));
            browser.updateCollaboration(List.of(editor("B", 0xFF336699, originalAvatar)));

            assertFalse(highlighted(first));
            assertTrue(highlighted(second));
            assertTrue(first.getChildWidgets().isEmpty());
            assertTrue(first.isSelected());
            Identifier nextAvatar = Identifier.icon("alex.png");
            browser.updateCollaboration(List.of(editor("B", 0xFF995522, nextAvatar)));

            assertSame(CollaborationVisuals.accent(0xFF995522), second.accentType);
            assertEquals(nextAvatar, avatar(second));
            assertSame(provider, field("treeProvider").get(browser));
            assertSame(first, container.getWidgets().getFirst());
            browser.updateCollaboration(List.of());

            assertFalse(highlighted(second));
            assertTrue(second.getChildWidgets().isEmpty());
            assertTrue(((Map<?, ?>) field("collaborationAvatars").get(browser)).isEmpty());
        } finally {
            browser.dispose();
        }
    }

    private static ReSyncContentBrowserWidget.BrowserResource resource(String id) {
        return new ReSyncContentBrowserWidget.BrowserResource("flow", id, id, id + ".json", 0, "", true);
    }

    private static ReSyncContentBrowserWidget.BrowserEditor editor(String id, int color, Identifier avatar) {
        return new ReSyncContentBrowserWidget.BrowserEditor("session",
            new ReSyncCollaborationClient.Identity("peer", "Peer", avatar.toString(), "test"),
            "flow", id, color, true, avatar, 1L);
    }

    private static FileEntryWidget row(ReSyncContentBrowserWidget browser,
                                       RemoteFileSystemProvider provider, Container container,
                                       RemotePath path, String name) throws Exception {
        FileEntryWidget row = FileEntryWidget.treeRowBuilder(
            new RemoteFileSystemProvider.FileEntry(path, false, "", "", name), provider, 180, 16, 0).build();
        container.addWidget(row);
        Method prepare = ReSyncContentBrowserWidget.class.getDeclaredMethod("prepareTreeNode",
            WorkspaceTreeExplorer.NodeRef.class, FileEntryWidget.class);
        prepare.setAccessible(true);
        prepare.invoke(browser, new WorkspaceTreeExplorer.NodeRef(path, false, name, "", "", false), row);
        return row;
    }

    private static Identifier avatar(FileEntryWidget row) {
        WidgetComposite badge = (WidgetComposite) row.getChildWidgets().iterator().next();
        return ((ImageWidget) badge.getChildWidgets().iterator().next()).getImageId();
    }

    private static boolean highlighted(FileEntryWidget row) throws Exception {
        Field field = FileEntryWidget.class.getDeclaredField("persistentHighlight");
        field.setAccessible(true);
        return field.getBoolean(row);
    }

    private static Field field(String name) throws Exception {
        Field field = ReSyncContentBrowserWidget.class.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }
}
