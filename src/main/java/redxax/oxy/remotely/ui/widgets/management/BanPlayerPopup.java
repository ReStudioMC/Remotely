package redxax.oxy.remotely.ui.widgets.management;

import redxax.oxy.remotely.data.player.model.UnifiedPlayer;
import restudio.rescreen.ui.core.Screen;
import restudio.rescreen.ui.widgets.PopupWidget;
import restudio.rescreen.ui.widgets.TextInputWidget;
import restudio.rescreen.ui.widgets.ToggleWidget;
import restudio.rescreen.util.Notification;

public class BanPlayerPopup extends PopupWidget {

    public BanPlayerPopup(Screen parent, UnifiedPlayer player, PlayerManagerController controller) {
        super(0, 0, 350, 200, "Ban " + player.getName());
        setLayer(500);

        resizable = true;

        var ref = new Object() {
            TextInputWidget reasonInput = new TextInputWidget.Builder().placeholder("Reason for ban").build();
        };
        ToggleWidget ipBanToggle = new ToggleWidget.Builder().toggled(false).build();
        Runnable banAction = () -> {
            String reason = ref.reasonInput.getText();
            boolean ipBan = ipBanToggle.getValue();
            controller.banPlayer(player, reason, ipBan).thenRun(() -> parent.execute(() ->
                    new Notification(player.getName() + " Has Been Banned", "Click Here To Unban", Notification.Type.SUCCESS, () -> controller.unbanPlayer(player))));
            hide();
        };
        ref.reasonInput = new TextInputWidget.Builder().placeholder("Reason for ban").onEnter(banAction).build();

        addRow("Reason", ref.reasonInput);
        if (player.getIp().getValue() != null && !player.getIp().getValue().isEmpty()) {
            addRow(new PopupWidget.PopupRow.Builder("IP Ban", ipBanToggle).contentWidth().build());
        }

        addTitleAction("Confirm Ban", banAction, PopupWidget.TitleActionRole.DESTRUCTIVE);

        parent.addDrawableChild(this);
        this.show();
    }
}
