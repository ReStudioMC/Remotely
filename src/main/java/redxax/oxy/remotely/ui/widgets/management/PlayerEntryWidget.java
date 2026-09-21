package redxax.oxy.remotely.ui.widgets.management;

import redxax.oxy.remotely.util.BrowserSafeState;

import redxax.oxy.remotely.data.integrations.luckperms.ReSyncLuckPermsClient;
import restudio.resync.permissions.LuckPermsManagementContract.PageRequest;
import redxax.oxy.remotely.data.managed.PlayerAction;
import redxax.oxy.remotely.data.player.model.PlayerAvatarUrl;
import redxax.oxy.remotely.data.player.model.UnifiedPlayer;
import redxax.oxy.remotely.RemotelyClient;
import restudio.rescreen.platform.IDrawContext;
import restudio.rescreen.theme.ThemeColor;
import restudio.rescreen.theme.ThemeManager;
import restudio.rescreen.ui.core.ScreenManager;
import restudio.rescreen.ui.widgets.*;
import restudio.rescreen.util.Identifier;
import restudio.rescreen.util.TimeUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;


public class PlayerEntryWidget extends MountableButtonWidget {

    private UnifiedPlayer player;
    private final PlayerManagerController controller;
    private final Identifier opIcon = Identifier.icon("op.png");
    private final Identifier deopIcon = Identifier.icon("deop.png");
    private String faceName;
    private boolean faceRequested;

    private String cachedPrefix = "";
    private String cachedSuffix = "";
    private String cachedGroup = "";
    private boolean lpDataRequested = false;

    private SquareButtonWidget kickButton;
    private SquareButtonWidget banButton;
    private SquareButtonWidget opButton;
    private SquareButtonWidget infoButton;
    private final List<SquareButtonWidget> actionButtons = new ArrayList<>();
    private boolean buttonsInitialized = false;
    private int lastActionsHash = Integer.MIN_VALUE;

    public PlayerEntryWidget(UnifiedPlayer player, PlayerManagerController controller) {
        super(player.getName(), "", "", BrowserSafeState.list(), null);
        setCursorHoverReactive(true);
        this.player = player;
        this.controller = controller;
        this.ClickableWhenInactive = true;
        setHeight(30);
        iconSize = 26;

        setupButtons();
    }

    private void setupButtons() {
        mountedWidgets.clear();
        kickButton = new SquareButtonWidget.Builder()
            .imagePath("delete.png").size(18, 18).hint("Kick Player").accentType(ThemeManager.getAccent("danger"))
            .onClick(() -> controller.kickPlayer(player, "Kicked by operator")).build();

        banButton = new SquareButtonWidget.Builder().imagePath("close.png").size(18, 18).hint("Ban Player")
            .accentType(ThemeManager.getAccent("danger")).onClick(() -> {
                if (player.getBan().getValue() != null) {
                    controller.unbanPlayer(player);
                } else {
                    new BanPlayerPopup(ScreenManager.getInstance().getCurrentScreen(), player, controller);
                }
            }).build();

        opButton = new SquareButtonWidget.Builder()
            .identifier(opIcon).size(18, 18).hint("Op Player")
            .accentType(ThemeManager.getAccent("calm"))
            .onClick(() -> controller.toggleOp(player)).build();

        infoButton = new SquareButtonWidget.Builder()
            .imagePath("info.png").size(18, 18).hint("Player Data")
            .accentType(ThemeManager.getAccent("calm"))
            .onClick(this::openPlayerDataPopup)
            .build();
        infoButton.active = true;

        updateButtonsState(controller.isServerRunning());
    }

    public void update(UnifiedPlayer newPlayerState) {
        this.player = newPlayerState;
        this.name = player.getName() != null ? player.getName() : "Unknown";
        boolean serverRunning = controller.isServerRunning();
        updateButtonsState(serverRunning);
    }

    private void updateButtonsState(boolean serverRunning) {
        List<PlayerAction> actions = controller.getPlayerActions();
        kickButton.active = player.isOnline() && serverRunning;
        boolean isBanned = player.getBan().getValue() != null;
        boolean isOp = player.isOp();
        updateBanButton(isBanned, serverRunning);
        updateOpButton(isOp, serverRunning);
        int actionsHash = hashActions(actions);
        if (!buttonsInitialized || actionsHash != lastActionsHash) {
            rebuildActionButtons(actions);
            mountedWidgets.clear();
            mountedWidgets.addAll(actionButtons);
            mountedWidgets.add(infoButton);
            mountedWidgets.add(banButton);
            mountedWidgets.add(kickButton);
            mountedWidgets.add(opButton);
            buttonsInitialized = true;
            lastActionsHash = actionsHash;
        }
        for (SquareButtonWidget actionButton : actionButtons) {
            actionButton.active = serverRunning;
        }
    }

    private void updateBanButton(boolean isBanned, boolean serverRunning) {
        banButton.setIcon(Identifier.icon(isBanned ? "heart.png" : "close.png"));
        banButton.hint = isBanned ? "Unban Player" : "Ban Player";
        banButton.accentType = isBanned ? ThemeManager.getAccent("nice") : ThemeManager.getAccent("danger");
        banButton.active = serverRunning;
    }

    private void updateOpButton(boolean isOp, boolean serverRunning) {
        opButton.setIcon(isOp ? deopIcon : opIcon);
        opButton.hint = isOp ? "De-Op Player" : "Op Player";
        opButton.active = serverRunning;
    }

    private void rebuildActionButtons(List<PlayerAction> actions) {
        actionButtons.clear();
        if (actions == null) return;
        for (PlayerAction action : actions) {
            SquareButtonWidget actionButton = new SquareButtonWidget.Builder().identifier(Identifier.icon(action.icon)).size(18, 18).hint(action.name).onClick(() -> {
                List<String> variables = findCustomVariables(action.command);
                if (variables.isEmpty()) {
                    controller.runCustomCommand(player, action.command);
                } else {
                    showVariableInputPopup(player, action, variables);
                }
            }).build();
            actionButtons.add(actionButton);
        }
    }

    private int hashActions(List<PlayerAction> actions) {
        if (actions == null || actions.isEmpty()) return 0;
        int hash = 1;
        for (PlayerAction action : actions) {
            hash = 31 * hash + Objects.hash(action.name, action.icon, action.command);
        }
        return hash;
    }

    public UnifiedPlayer getPlayer() {
        return player;
    }

    @Override
    public void tick() {
        super.tick();
        String nextFaceName = player.getName();
        if ((!faceRequested || !Objects.equals(faceName, nextFaceName))
                && RemotelyClient.INSTANCE != null && RemotelyClient.INSTANCE.getHost() != null) {
            String source = PlayerAvatarUrl.resolve(nextFaceName, player.getUuid(), 64);
            Identifier fetchedFaceId = source.isBlank() ? null : RemotelyClient.INSTANCE.getHost().registerRemoteImage(source);
            if (fetchedFaceId != null) {
                faceName = nextFaceName;
                faceRequested = true;
                setGeneratedIcon(fetchedFaceId);
                this.iconSize = 26;
            }
        }

        if (!lpDataRequested) {
            ReSyncLuckPermsClient lp = controller.getLuckPermsClient();
            if (lp != null && lp.isAvailable()) {
                lpDataRequested = true;
                lp.users(new PageRequest("", 1, player.getUuid().toString())).thenAccept(page -> {
                    if (!page.items().isEmpty()) {
                        var user = page.items().getFirst();
                        cachedPrefix = user.prefix();
                        cachedSuffix = user.suffix();
                        cachedGroup = user.primaryGroup();
                    }
                });
            }
        }

    }

    @Override
    protected void drawContent(IDrawContext ctx, int mouseX, int mouseY) {
        StringBuilder displayName = new StringBuilder();
        if (!cachedPrefix.isEmpty()) displayName.append(cachedPrefix);
        displayName.append(player.getName() != null ? player.getName() : "Unknown");
        if (!cachedSuffix.isEmpty()) displayName.append(cachedSuffix);

        name = displayName.toString().replace("&", "§");
        hiddenText = cachedGroup;

        boolean isBanned = player.getBan().getValue() != null;
        String statusText = "";
        if (isBanned) {
            String reason = player.getBan().getValue().reason();
            String expires = player.getBan().getValue().expires();
            description = "Banned For " + reason + " | Expires: " + expires;
            accentType = ThemeManager.getAccent("danger");
        } else if (player.isOnline()) {
            statusText = "Online";
            if (player.isOp()) {
                statusText += " | Operator";
                accentType = ThemeManager.getAccent("calm");
            }
            else accentType = ThemeManager.getDefaultAccent();
            active = true;
            description = statusText;
        } else {
            description = "Offline";
            if (player.isOp()) description += " | Operator";
            long lastSeen = player.getLastSeenValue();
            if (lastSeen > 0) {
                description += " | Last seen: " + TimeUtils.timeSense(lastSeen);
            }
            accentType = ThemeManager.getDefaultAccent();
            active = false;
        }
        titleColor = player.isOnline() ? ThemeManager.getColor(ThemeColor.text) : ThemeManager.getColor(ThemeColor.textDark);

        super.drawContent(ctx, mouseX, mouseY);
    }

    private void openPlayerDataPopup() {
        new PlayerDataPopup(ScreenManager.getInstance().getCurrentScreen(), player, controller);
    }

    private List<String> findCustomVariables(String command) {
        List<String> variables = new ArrayList<>();
        Pattern pattern = Pattern.compile("\\$([a-zA-Z0-9_]+)");
        Matcher matcher = pattern.matcher(command);
        while (matcher.find()) {
            String var = matcher.group(1);
            if (!"name".equalsIgnoreCase(var) && !"uuid".equalsIgnoreCase(var) && !variables.contains(var)) {
                variables.add(var);
            }
        }
        return new ArrayList<>(variables);
    }

    private void showVariableInputPopup(UnifiedPlayer player, PlayerAction action, List<String> variables) {
        PopupWidget.Builder builder = new PopupWidget.Builder("Execute: " + action.name)
            .width(300).setAntiOutOfBound(true).setResizable(true);

        Map<String, TextInputWidget> inputs = new HashMap<>();
        Runnable execute = () -> {
            String command = action.command;
            for (Map.Entry<String, TextInputWidget> entry : inputs.entrySet()) {
                String value = entry.getValue().getText();
                command = command.replace("$" + entry.getKey(), value);
            }
            controller.runCustomCommand(player, command);
            builder.getWidget().setVisible(false);
        };
        for (String var : variables) {
            TextInputWidget input = new TextInputWidget.Builder().placeholder("Enter value for $" + var).build();
            inputs.put(var, input);
            input.onEnter = () -> {
                int index = variables.indexOf(var);
                if (index >= 0 && index < variables.size() - 1) {
                    TextInputWidget nextWidget = inputs.get(variables.get(index + 1));
                    ((PopupWidget) builder.getWidget()).setFocusedWidget(nextWidget);
                } else {
                    execute.run();
                }
            };
            builder.addRow("", input);
        }
        builder.addTitleAction("Execute", execute, PopupWidget.TitleActionRole.PRIMARY);
        PopupWidget popup = builder.build();
        ScreenManager.getInstance().getCurrentScreen().addDrawableChild(popup);
        popup.show();
    }
}
