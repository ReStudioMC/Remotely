package redxax.oxy.remotely.data.player.source;

import redxax.oxy.remotely.data.player.PlayerService;
import redxax.oxy.remotely.data.player.PlayerUpdateBatch;
import redxax.oxy.remotely.data.player.model.UnifiedPlayer;
import restudio.rebase.backend.feature.PlayerManagementFeature;
import restudio.rescreen.logging.LogSource;
import restudio.rescreen.logging.LogTypes;
import restudio.rescreen.logging.ReLog;

import java.util.List;
import restudio.rescreen.platform.Async;

public class BackendPlayerSource implements IPlayerSource {
    private final PlayerManagementFeature feature;
    private PlayerService service;
    private boolean enabled = false;

    public BackendPlayerSource(PlayerManagementFeature feature) {
        this.feature = feature;
    }

    @Override
    public void init(PlayerService context) {
        this.service = context;
    }

    @Override
    public void enable() {
        enabled = true;
        refresh();
    }

    @Override
    public void disable() {
        enabled = false;
    }

    @Override
    public int getPriority() {
        return 25;
    }

    @Override
    public void refresh() {
        if (!enabled || service == null) return;

        if (!feature.supportsOnlinePlayers()) {
            processPlayers(List.of());
            return;
        }
        feature.getOnlinePlayers().thenAccept(this::processPlayers)
                .exceptionally(e -> {
                    ReLog.logger(LogTypes.NETWORK).source(LogSource.application("Remotely")).component(BackendPlayerSource.class).operation("Refresh Players").error("Could not fetch players from backend", e);
                    return null;
                });
    }

    private void processPlayers(List<PlayerManagementFeature.SimplePlayer> onlinePlayers) {
        if (onlinePlayers == null) return;

        PlayerUpdateBatch batch = new PlayerUpdateBatch("backend", getPriority());

        for (PlayerManagementFeature.SimplePlayer sp : onlinePlayers) {
            PlayerUpdateBatch.PlayerUpdate update = new PlayerUpdateBatch.PlayerUpdate(sp.uuid(), sp.name());
            update.setOnline(true);
            batch.add(update);
        }

        service.getRegistry().getAll().stream()
            .filter(UnifiedPlayer::isOnline)
            .filter(p -> onlinePlayers.stream().noneMatch(sp -> sp.uuid().equals(p.getUuid())))
            .forEach(p -> {
                if (!"backend".equalsIgnoreCase(p.getOnline().getSource())) {
                    return;
                }
                PlayerUpdateBatch.PlayerUpdate update = new PlayerUpdateBatch.PlayerUpdate(p.getUuid(), p.getName());
                update.setOnline(false);
                batch.add(update);
            });

        service.submitUpdate(batch);
    }
}
