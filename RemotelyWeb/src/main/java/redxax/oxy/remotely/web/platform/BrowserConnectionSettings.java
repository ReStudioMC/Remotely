package redxax.oxy.remotely.web.platform;

import redxax.oxy.remotely.RemotelyServerApi;
import restudio.rebase.restudio.api.models.ServerModels;
import restudio.rescreen.theme.ThemeManager;
import restudio.rescreen.ui.core.Screen;
import restudio.rescreen.ui.core.ScreenManager;
import restudio.rescreen.ui.settings.Setting;
import restudio.rescreen.ui.widgets.AnimatedButton;
import restudio.rescreen.ui.widgets.MountableButtonWidget;
import restudio.rescreen.ui.widgets.PopupWidget;
import restudio.rescreen.ui.widgets.SquareButtonWidget;
import restudio.rescreen.ui.widgets.TextInputWidget;
import restudio.rescreen.util.Notification;

import java.util.List;
import java.util.Objects;

final class BrowserConnectionSettings {
    private final BrowserRemotelyServerApi api;

    BrowserConnectionSettings(BrowserRemotelyServerApi api) {
        this.api = api;
    }

    List<Setting> getSettings() {
        Setting.Builder connections = new Setting.Builder("SSH And SFTP");
        connections.addRow("", new AnimatedButton.Builder().label("Server Connections")
                .active(available()).onClick(this::showServers).build());
        connections.addRow("", new AnimatedButton.Builder().label("Personal Credentials")
                .active(available()).onClick(this::showCredentials).build());
        return List.of(connections.build());
    }

    private boolean available() {
        return api != null && BrowserLaunchSession.authenticated() && !BrowserLaunchSession.metadata().demo();
    }

    private void showServers() {
        Screen screen = activeScreen();
        if (screen == null || !available()) return;
        String subject = BrowserLaunchSession.metadata().subjectId();
        api.getServers().whenComplete((servers, failure) -> ScreenManager.getInstance().execute(() -> {
            if (!current(screen, subject)) return;
            if (failure != null) {
                error("Server Connections Are Unavailable");
                return;
            }
            PopupWidget.Builder builder = new PopupWidget.Builder("Server Connections").width(440).setResizable(false);
            List<ServerModels.ClientServerView> nativeServers = servers == null ? List.of() : servers.stream()
                    .filter(Objects::nonNull).filter(this::nativeServer).toList();
            if (nativeServers.isEmpty()) builder.addMarkdown("", "No Native Servers Are Available.");
            for (ServerModels.ClientServerView server : nativeServers) {
                String id = server.identifier;
                String label = server.name == null || server.name.isBlank() ? id : server.name;
                MountableButtonWidget row = new MountableButtonWidget.Builder(label)
                        .description("View Connection Details")
                        .addButton(new SquareButtonWidget.Builder().imagePath("external.png").hint("View Connection")
                                .onClick(() -> showConnection(id)).build()).build();
                row.setHeight(30);
                builder.addRow("", row);
            }
            show(screen, builder);
        }));
    }

    private void showConnection(String serverId) {
        Screen screen = activeScreen();
        if (screen == null || !available()) return;
        String subject = BrowserLaunchSession.metadata().subjectId();
        api.getServer(serverId).thenCompose(server -> api.getServerCapabilities(serverId).exceptionally(ignored -> null)
                .thenApply(capabilities -> new Connection(server, capabilities)))
                .whenComplete((connection, failure) -> ScreenManager.getInstance().execute(() -> {
                    if (!current(screen, subject)) return;
                    if (failure != null || connection == null || connection.server() == null
                            || !nativeServer(connection.server())) {
                        error("Connection Details Are Unavailable");
                        return;
                    }
                    ServerModels.ClientServerView server = connection.server();
                    if (server.sftpIp == null || server.sftpIp.isBlank() || server.sftpPort < 1
                            || server.sftpUser == null || server.sftpUser.isBlank()) {
                        error("SFTP Connection Details Are Unavailable");
                        return;
                    }
                    PopupWidget.Builder builder = new PopupWidget.Builder("Server Connection").width(460).setResizable(false);
                    boolean console = connection.capabilities() != null
                            && connection.capabilities().action("control.console").supported();
                    builder.addMarkdown("", console ? "Personal Credentials: SSH And SFTP, While This Server Grants Console Access."
                            : "Personal Credentials: SFTP. SSH Requires Console Access.");
                    detail(builder, "Host", server.sftpIp);
                    detail(builder, "Port", String.valueOf(server.sftpPort));
                    detail(builder, "Username", server.sftpUser);
                    builder.addRow("", new AnimatedButton.Builder().label("Get Temporary SFTP Password")
                            .onClick(() -> showTemporaryPassword(serverId)).build());
                    show(screen, builder);
                }));
    }

    private void showTemporaryPassword(String serverId) {
        Screen screen = activeScreen();
        if (screen == null || !available()) return;
        String subject = BrowserLaunchSession.metadata().subjectId();
        api.getSftpToken(serverId).whenComplete((token, failure) -> ScreenManager.getInstance().execute(() -> {
            if (!current(screen, subject)) return;
            if (failure != null || token == null || token.isBlank()) {
                error("Temporary SFTP Password Is Unavailable");
                return;
            }
            showSecret(screen, "Temporary SFTP Password", token);
        }));
    }

    private void showCredentials() {
        Screen screen = activeScreen();
        if (screen == null || !available()) return;
        String subject = BrowserLaunchSession.metadata().subjectId();
        api.getPersonalCredentials().whenComplete((credentials, failure) -> ScreenManager.getInstance().execute(() -> {
            if (!current(screen, subject)) return;
            if (failure != null) {
                error("Personal Credentials Are Unavailable");
                return;
            }
            PopupWidget.Builder builder = new PopupWidget.Builder("Personal Credentials").width(460).setResizable(false);
            builder.addMarkdown("", "These credentials belong to your account. Each native server checks your current access when you connect."
                    + " SSH requires Console access; SFTP requires File access.");
            builder.addRow("", new AnimatedButton.Builder().label("Create Personal Credential")
                    .onClick(this::showCreate).build());
            if (credentials == null || credentials.isEmpty()) builder.addMarkdown("", "No Personal Credentials.");
            else for (BrowserRemotelyServerApi.PersonalCredential credential : credentials) {
                String status = credential.active() ? "Created " + credential.createdAt() : "Revoked";
                MountableButtonWidget.Builder row = new MountableButtonWidget.Builder(credential.name())
                        .description(status).hiddenText(credential.lastUsedAt() == null || credential.lastUsedAt().isBlank()
                                ? "Never Used" : "Last Used " + credential.lastUsedAt());
                if (credential.active()) row.addButton(new SquareButtonWidget.Builder().imagePath("delete.png")
                        .hint("Revoke Credential").accentType(ThemeManager.getAccent("danger"))
                        .onClick(() -> showRevoke(credential)).build());
                MountableButtonWidget widget = row.build();
                widget.setHeight(30);
                builder.addRow("", widget);
            }
            show(screen, builder);
        }));
    }

    private void showCreate() {
        Screen screen = activeScreen();
        if (screen == null || !available()) return;
        String subject = BrowserLaunchSession.metadata().subjectId();
        PopupWidget.Builder builder = new PopupWidget.Builder("Create Personal Credential").width(360).setResizable(false);
        TextInputWidget name = new TextInputWidget.Builder().placeholder("Name This Device").size(260, 20).build();
        boolean[] pending = {false};
        builder.addRow("Device Name", name);
        builder.addTitleAction("Create", () -> {
            if (pending[0]) return;
            String value = name.getText() == null ? "" : name.getText().strip();
            if (value.isBlank() || value.length() > 64 || value.chars().anyMatch(Character::isISOControl)) {
                error("Enter A Device Name With Up To 64 Characters");
                return;
            }
            pending[0] = true;
            api.createPersonalCredential(value).whenComplete((issued, failure) -> ScreenManager.getInstance().execute(() -> {
                pending[0] = false;
                if (!current(screen, subject)) return;
                if (failure != null || issued == null || issued.token() == null || issued.token().isBlank()) {
                    error("Personal Credential Could Not Be Created");
                    return;
                }
                PopupWidget popup = builder.getWidget();
                if (popup != null) popup.hide();
                showSecret(screen, "Personal Credential", issued.token());
            }));
        }, PopupWidget.TitleActionRole.PRIMARY);
        closeCurrentPopup();
        show(screen, builder);
    }

    private void showRevoke(BrowserRemotelyServerApi.PersonalCredential credential) {
        Screen screen = activeScreen();
        if (screen == null || !available()) return;
        String subject = BrowserLaunchSession.metadata().subjectId();
        PopupWidget.Builder builder = new PopupWidget.Builder("Revoke Personal Credential").width(360).setResizable(false);
        builder.addMarkdown("", "Revoke This Credential? New SSH And SFTP Connections Using It Will Be Denied.");
        builder.addTitleAction("Revoke", () -> api.revokePersonalCredential(credential.id())
                .whenComplete((ignored, failure) -> ScreenManager.getInstance().execute(() -> {
                    if (!current(screen, subject)) return;
                    if (failure != null) {
                        error("Personal Credential Could Not Be Revoked");
                        return;
                    }
                    PopupWidget popup = builder.getWidget();
                    if (popup != null) popup.hide();
                    new Notification("Revoked", "Personal Credential Revoked", Notification.Type.SUCCESS);
                    showCredentials();
                })), PopupWidget.TitleActionRole.DESTRUCTIVE);
        closeCurrentPopup();
        show(screen, builder);
    }

    private static void detail(PopupWidget.Builder builder, String label, String value) {
        TextInputWidget field = new TextInputWidget.Builder().text(value).size(300, 20).build();
        SquareButtonWidget copy = new SquareButtonWidget.Builder().imagePath("clipboard.png").hint("Copy " + label)
                .onClick(() -> ScreenManager.getInstance().clipboardHandler().setClipboard(value)).build();
        builder.addRow(label.toLowerCase(), label, field, copy);
    }

    private static void showSecret(Screen screen, String title, String secret) {
        PopupWidget.Builder builder = new PopupWidget.Builder(title).width(460).setResizable(false);
        builder.addMarkdown("", "Copy This Credential Now. It Will Not Be Shown Again.");
        builder.addRow("Credential", new TextInputWidget.Builder().text(secret).size(380, 20).build());
        builder.addTitleAction("Copy", () -> ScreenManager.getInstance().clipboardHandler().setClipboard(secret),
                PopupWidget.TitleActionRole.PRIMARY);
        show(screen, builder);
    }

    private static Screen activeScreen() {
        ScreenManager manager = ScreenManager.getInstance();
        var overlay = manager.getDesktopWindowsOverlay();
        var window = overlay == null ? null : overlay.getActiveWindow();
        return window == null || !window.isVisible() || window.isMinimized() ? manager.getCurrentScreen() : window.getScreen();
    }

    private static boolean current(Screen screen, String subject) {
        ScreenManager manager = ScreenManager.getInstance();
        var overlay = manager.getDesktopWindowsOverlay();
        return BrowserLaunchSession.authenticated() && !BrowserLaunchSession.metadata().demo()
                && (manager.getCurrentScreen() == screen || overlay != null && overlay.isScreenInWindow(screen))
                && Objects.equals(BrowserLaunchSession.metadata().subjectId(), subject);
    }

    private boolean nativeServer(ServerModels.ClientServerView server) {
        return server != null && server.identifier != null && !server.identifier.isBlank()
                && server.sftpUser != null && server.sftpUser.startsWith("restudio.");
    }

    private static void show(Screen screen, PopupWidget.Builder builder) {
        PopupWidget popup = builder.build();
        screen.addDrawableChild(popup);
        popup.show();
    }

    private static void closeCurrentPopup() {
        var overlay = ScreenManager.getInstance().getPopupOverlay();
        PopupWidget popup = overlay.getActivePopup();
        if (popup != null) overlay.hidePopup(popup);
    }

    private static void error(String message) {
        new Notification("Connection Access", message, Notification.Type.ERROR);
    }

    private record Connection(ServerModels.ClientServerView server, RemotelyServerApi.ServerCapabilities capabilities) {
    }
}
