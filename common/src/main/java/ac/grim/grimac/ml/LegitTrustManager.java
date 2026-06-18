package ac.grim.grimac.ml;

import ac.grim.grimac.api.GrimUser;
import ac.grim.grimac.player.GrimPlayer;
import ac.grim.grimac.utils.anticheat.LogUtil;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class LegitTrustManager {

    private final Set<UUID> manualLegit = ConcurrentHashMap.newKeySet();
    private final MlConfig config;
    private Path legitListFile;

    public LegitTrustManager(MlConfig config) {
        this.config = config;
    }

    public void init(Path dataFolder) {
        legitListFile = dataFolder.resolve("ml").resolve("legit-players.yml");
        load();
    }

    public void onJoin(GrimUser user) {
        // refresh op trust state on join
    }

    public boolean isTrusted(GrimPlayer player) {
        if (player == null || player.uuid == null) return false;
        if (manualLegit.contains(player.uuid)) return true;
        return config.isAutoTrustOp() && player.platformPlayer != null && player.platformPlayer.isOp();
    }

    public boolean isManualLegit(UUID uuid) {
        return uuid != null && manualLegit.contains(uuid);
    }

    public boolean isOpTrusted(GrimPlayer player) {
        return config.isAutoTrustOp() && player.platformPlayer != null && player.platformPlayer.isOp();
    }

    public boolean markLegit(UUID uuid) {
        if (uuid == null) return false;
        boolean added = manualLegit.add(uuid);
        if (added) save();
        return added;
    }

    public boolean unmarkLegit(UUID uuid) {
        if (uuid == null) return false;
        boolean removed = manualLegit.remove(uuid);
        if (removed) save();
        return removed;
    }

    public Set<UUID> manualLegitPlayers() {
        return Collections.unmodifiableSet(manualLegit);
    }

    public void load() {
        manualLegit.clear();
        if (legitListFile == null || !Files.exists(legitListFile)) return;
        try (BufferedReader reader = Files.newBufferedReader(legitListFile)) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                try {
                    manualLegit.add(UUID.fromString(line));
                } catch (IllegalArgumentException ignored) {
                }
            }
        } catch (IOException e) {
            LogUtil.warn("failed to load legit player list: " + e.getMessage());
        }
    }

    private void save() {
        if (legitListFile == null) return;
        try {
            Files.createDirectories(legitListFile.getParent());
            try (BufferedWriter writer = Files.newBufferedWriter(legitListFile)) {
                writer.write("# manually marked legit players (one uuid per line)\n");
                for (UUID uuid : manualLegit) {
                    writer.write(uuid.toString());
                    writer.newLine();
                }
            }
        } catch (IOException e) {
            LogUtil.warn("failed to save legit player list: " + e.getMessage());
        }
    }
}
