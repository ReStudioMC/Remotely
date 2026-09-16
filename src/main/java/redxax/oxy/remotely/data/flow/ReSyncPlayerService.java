package redxax.oxy.remotely.data.flow;

import redxax.oxy.remotely.util.BrowserSafeState;
import redxax.oxy.remotely.data.flow.player.PlayerDossier;
import redxax.oxy.remotely.data.flow.player.PlayerTrackingUpdate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.Map;

public class ReSyncPlayerService {
    private final Map<String, PlayerDossier> playerDossierCache = BrowserSafeState.map();
    private final Map<String, Long> snapshotRevisions = BrowserSafeState.map();

    public List<String> getOnlinePlayerNamesForServer(String serverId) {
        if (serverId == null || serverId.isBlank()) {
            return List.of();
        }
        String prefix = serverId + ":";
        Map<String, String> playerNames = new LinkedHashMap<>();
        for (Map.Entry<String, PlayerDossier> entry : playerDossierCache.entrySet()) {
            if (!entry.getKey().startsWith(prefix)) {
                continue;
            }
            PlayerDossier dossier = entry.getValue();
            if (dossier == null || !dossier.isOnline()) {
                continue;
            }
            String playerName = dossier.getPlayerName();
            if (playerName == null || playerName.isBlank()) {
                continue;
            }
            playerNames.putIfAbsent(playerName.toLowerCase(Locale.ROOT), playerName);
        }
        List<String> values = new ArrayList<>(playerNames.values());
        values.sort(String.CASE_INSENSITIVE_ORDER);
        return values;
    }

    public List<PlayerDossier> getOnlinePlayersForServer(String serverId) {
        if (serverId == null || serverId.isBlank()) {
            return List.of();
        }
        String prefix = serverId + ":";
        Map<String, PlayerDossier> players = new LinkedHashMap<>();
        for (Map.Entry<String, PlayerDossier> entry : playerDossierCache.entrySet()) {
            if (!entry.getKey().startsWith(prefix)) {
                continue;
            }
            PlayerDossier dossier = entry.getValue();
            if (dossier == null || !dossier.isOnline() || dossier.getPlayerName() == null || dossier.getPlayerName().isBlank()) {
                continue;
            }
            players.putIfAbsent(dossier.getPlayerName().toLowerCase(Locale.ROOT), dossier);
        }
        List<PlayerDossier> values = new ArrayList<>(players.values());
        values.sort((a, b) -> a.getPlayerName().compareToIgnoreCase(b.getPlayerName()));
        return values;
    }

    public PlayerDossier getPlayerDossier(String serverId, UUID playerId) {
        if (serverId == null || playerId == null) {
            return null;
        }
        return playerDossierCache.get(serverId + ":" + playerId);
    }

    public void applyPlayerTrackingUpdate(String serverId, PlayerTrackingUpdate update) {
        if (serverId == null || update == null) {
            return;
        }
        if ("snapshot".equalsIgnoreCase(update.getType())) {
            clearCache(serverId);
            for (PlayerDossier dossier : update.getDossiers()) {
                cachePlayerDossier(serverId, dossier);
            }
            snapshotRevisions.merge(serverId, 1L, Long::sum);
            return;
        }
        cachePlayerDossier(serverId, update.getDossier());
    }

    public void clearCache(String serverId) {
        String prefix = serverId + ":";
        playerDossierCache.keySet().removeIf(key -> key.startsWith(prefix));
    }

    public long snapshotRevision(String serverId) {
        return serverId == null ? 0L : snapshotRevisions.getOrDefault(serverId, 0L);
    }

    private void cachePlayerDossier(String serverId, PlayerDossier dossier) {
        if (serverId == null || dossier == null || dossier.getPlayerId() == null || dossier.getPlayerId().isBlank()) {
            return;
        }
        playerDossierCache.put(serverId + ":" + dossier.getPlayerId(), dossier);
    }
}
