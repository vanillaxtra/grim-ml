package ac.grim.grimac.ml;

import ac.grim.grimac.utils.anticheat.LogUtil;
import lombok.Getter;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

public final class MlTrainingSessionStore {

    private volatile @Nullable ExecutorService executor;

    private @Nullable Path databaseFile;
    private @Nullable Path archiveFolder;

    @Getter private volatile boolean active;
    @Getter private volatile @Nullable String sessionId;
    @Getter private volatile long startedAtMs;
    @Getter private volatile long stoppedAtMs;
    @Getter private volatile int liveSampleCount;

    public void init(Path dataFolder) {
        ensureExecutor();
        Path mlFolder = dataFolder.resolve("ml").resolve("training");
        databaseFile = mlFolder.resolve("session.sqlite");
        archiveFolder = mlFolder.resolve("archives");
        try {
            Files.createDirectories(archiveFolder);
        } catch (Exception e) {
            LogUtil.warn("failed to create ml training folder: " + e.getMessage());
            return;
        }
        runSync(this::initDatabase);
    }

    private Void initDatabase() {
        if (databaseFile == null) return null;
        try (Connection connection = openConnection(); Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS training_samples (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        occurred_epoch_ms INTEGER NOT NULL,
                        player_uuid TEXT,
                        check_stable_key TEXT,
                        check_name TEXT,
                        event_type TEXT,
                        activity TEXT,
                        vl REAL,
                        transaction_ping INTEGER,
                        tps_avg REAL,
                        mspt_avg REAL,
                        trusted INTEGER,
                        false_positive_label INTEGER,
                        measured_value REAL,
                        config_threshold REAL,
                        target_multiplier REAL,
                        sample_weight REAL,
                        player_state_bits INTEGER,
                        verbose_snapshot TEXT,
                        feature_json TEXT
                    )
                    """);
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS training_meta (
                        key TEXT PRIMARY KEY,
                        value TEXT NOT NULL
                    )
                    """);
            migrateSchema(connection);
            loadMeta(connection);
        } catch (Exception e) {
            LogUtil.error("failed to init ml training session store", e);
        }
        return null;
    }

    private void migrateSchema(Connection connection) throws Exception {
        if (!columnExists(connection, "event_type")) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("ALTER TABLE training_samples ADD COLUMN event_type TEXT");
                statement.execute("ALTER TABLE training_samples ADD COLUMN activity TEXT");
                statement.execute("ALTER TABLE training_samples ADD COLUMN player_state_bits INTEGER DEFAULT 0");
            }
        }
    }

    private boolean columnExists(Connection connection, String column) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("PRAGMA table_info(training_samples)");
             ResultSet resultSet = statement.executeQuery()) {
            while (resultSet.next()) {
                if (column.equalsIgnoreCase(resultSet.getString("name"))) return true;
            }
        }
        return false;
    }

    private void loadMeta(Connection connection) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("SELECT key, value FROM training_meta");
             ResultSet resultSet = statement.executeQuery()) {
            while (resultSet.next()) {
                String key = resultSet.getString("key");
                String value = resultSet.getString("value");
                switch (key) {
                    case "active" -> active = Boolean.parseBoolean(value);
                    case "session_id" -> sessionId = value.isEmpty() ? null : value;
                    case "started_at" -> startedAtMs = Long.parseLong(value);
                    case "stopped_at" -> stoppedAtMs = Long.parseLong(value);
                    default -> {
                    }
                }
            }
        }
        liveSampleCount = countSamples(connection);
    }

    public CompletableFuture<StartResult> startSessionAsync() {
        return supplyAsync(() -> {
            if (databaseFile == null) return new StartResult(false, "training store not ready");
            if (active) return new StartResult(false, "training already active");
            try (Connection connection = openConnection(); Statement statement = connection.createStatement()) {
                if (countSamples(connection) > 0) {
                    backupToArchive(connection);
                }
                statement.execute("DELETE FROM training_samples");
                sessionId = UUID.randomUUID().toString();
                startedAtMs = System.currentTimeMillis();
                stoppedAtMs = 0L;
                active = true;
                liveSampleCount = 0;
                writeMeta(connection);
                return new StartResult(true, sessionId);
            } catch (Exception e) {
                LogUtil.warn("failed to start ml training session: " + e.getMessage());
                return new StartResult(false, e.getMessage());
            }
        });
    }

    public CompletableFuture<StopResult> stopSessionAsync(MlConfig config) {
        return supplyAsync(() -> {
            if (databaseFile == null) return new StopResult(false, "training store not ready", 0, List.of(), null);
            if (!active) return new StopResult(false, "training not active", liveSampleCount, List.of(), null);
            try (Connection connection = openConnection()) {
                active = false;
                stoppedAtMs = System.currentTimeMillis();
                liveSampleCount = countSamples(connection);
                writeMeta(connection);
                Path archive = backupToArchive(connection);
                List<MlEventStore.TrainingSample> samples = loadTrainingSamples(config, connection);
                return new StopResult(true, "saved", samples.size(), samples, archive);
            } catch (Exception e) {
                LogUtil.warn("failed to stop ml training session: " + e.getMessage());
                return new StopResult(false, e.getMessage(), liveSampleCount, List.of(), null);
            }
        });
    }

    public void writeAsync(MlFeatureVector features, double vl, @Nullable UUID playerUuid) {
        if (databaseFile == null || !active) return;
        ensureExecutor().execute(() -> write(features, vl, playerUuid));
    }

    private void write(MlFeatureVector features, double vl, @Nullable UUID playerUuid) {
        if (databaseFile == null || !active) return;
        String sql = """
                INSERT INTO training_samples (
                    occurred_epoch_ms, player_uuid, check_stable_key, check_name, event_type, activity,
                    vl, transaction_ping, tps_avg, mspt_avg, trusted, false_positive_label,
                    measured_value, config_threshold, target_multiplier, sample_weight, player_state_bits,
                    verbose_snapshot, feature_json
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, System.currentTimeMillis());
            statement.setString(2, playerUuid == null ? null : playerUuid.toString());
            statement.setString(3, features.checkStableKey);
            statement.setString(4, features.checkName);
            statement.setString(5, features.eventType.name());
            statement.setString(6, features.activity);
            statement.setDouble(7, vl);
            statement.setInt(8, features.transactionPing);
            statement.setDouble(9, features.tpsAvg);
            statement.setDouble(10, features.msptAvg);
            statement.setInt(11, features.trusted ? 1 : 0);
            statement.setInt(12, features.falsePositiveLabel);
            statement.setDouble(13, features.measuredValue);
            statement.setDouble(14, features.configThreshold);
            statement.setDouble(15, features.targetMultiplier(config()));
            statement.setDouble(16, features.sampleWeight);
            statement.setInt(17, features.playerStateBits());
            statement.setString(18, features.verboseSnapshot);
            statement.setString(19, features.toJson());
            statement.executeUpdate();
            liveSampleCount++;
        } catch (Exception e) {
            LogUtil.warn("failed to write training sample: " + e.getMessage());
        }
    }

    public CompletableFuture<List<MlEventStore.TrainingSample>> loadTrainingSamplesAsync(MlConfig config) {
        return supplyAsync(() -> {
            if (databaseFile == null) return List.of();
            try (Connection connection = openConnection()) {
                return loadTrainingSamples(config, connection);
            } catch (Exception e) {
                LogUtil.warn("failed to load training session samples: " + e.getMessage());
                return List.of();
            }
        });
    }

    public int savedSampleCount() {
        return liveSampleCount;
    }

    public java.util.Map<String, Integer> countByActivity() {
        try {
            java.util.Map<String, Integer> counts = supplyAsync(() -> {
                java.util.Map<String, Integer> result = new java.util.HashMap<>();
                if (databaseFile == null) return result;
                try (Connection connection = openConnection();
                     PreparedStatement statement = connection.prepareStatement(
                             "SELECT activity, COUNT(*) AS total FROM training_samples GROUP BY activity");
                     ResultSet resultSet = statement.executeQuery()) {
                    while (resultSet.next()) {
                        String activity = resultSet.getString("activity");
                        result.put(activity == null ? "unknown" : activity, resultSet.getInt("total"));
                    }
                } catch (Exception ignored) {
                }
                return result;
            }).join();
            return counts != null ? counts : java.util.Map.of();
        } catch (Exception e) {
            return java.util.Map.of();
        }
    }

    public void shutdown() {
        ExecutorService current = executor;
        if (current == null) return;
        current.shutdown();
        try {
            current.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        executor = null;
    }

    private synchronized ExecutorService ensureExecutor() {
        if (executor == null || executor.isShutdown()) {
            executor = Executors.newSingleThreadExecutor(r -> {
                Thread thread = new Thread(r, "grim-ml-training");
                thread.setDaemon(true);
                return thread;
            });
        }
        return executor;
    }

    private List<MlEventStore.TrainingSample> loadTrainingSamples(MlConfig config, Connection connection) throws Exception {
        if (!tableExists(connection, "training_samples")) return List.of();
        String sql = """
                SELECT measured_value, config_threshold, transaction_ping, tps_avg, mspt_avg,
                       false_positive_label, sample_weight, check_stable_key, activity, player_state_bits
                FROM training_samples
                ORDER BY id
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet resultSet = statement.executeQuery()) {
            return MlTrainingSampleUtil.loadAll(resultSet, config);
        }
    }

    private int countSamples(Connection connection) throws Exception {
        if (!tableExists(connection, "training_samples")) return 0;
        try (PreparedStatement statement = connection.prepareStatement("SELECT COUNT(*) FROM training_samples");
             ResultSet resultSet = statement.executeQuery()) {
            if (resultSet.next()) return resultSet.getInt(1);
        }
        return 0;
    }

    private @Nullable Path backupToArchive(Connection connection) {
        if (sessionId == null || archiveFolder == null || databaseFile == null) return null;
        try {
            Path archive = archiveFolder.resolve("session-" + startedAtMs + "-" + sessionId + ".sqlite");
            String archivePath = archive.toAbsolutePath().toString().replace('\\', '/').replace("'", "''");
            try (Statement statement = connection.createStatement()) {
                statement.execute("PRAGMA wal_checkpoint(FULL)");
                statement.execute("VACUUM INTO '" + archivePath + "'");
            }
            setMeta(connection, "latest_archive", archive.getFileName().toString());
            return archive;
        } catch (Exception e) {
            LogUtil.warn("failed to archive training session: " + e.getMessage());
            return null;
        }
    }

    private void writeMeta(Connection connection) throws Exception {
        setMeta(connection, "active", Boolean.toString(active));
        setMeta(connection, "session_id", sessionId == null ? "" : sessionId);
        setMeta(connection, "started_at", Long.toString(startedAtMs));
        setMeta(connection, "stopped_at", Long.toString(stoppedAtMs));
        setMeta(connection, "sample_count", Integer.toString(liveSampleCount));
    }

    private void setMeta(Connection connection, String key, String value) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO training_meta(key, value) VALUES(?, ?) ON CONFLICT(key) DO UPDATE SET value = excluded.value")) {
            statement.setString(1, key);
            statement.setString(2, value);
            statement.executeUpdate();
        }
    }

    private boolean tableExists(Connection connection, String table) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT name FROM sqlite_master WHERE type='table' AND name = ?")) {
            statement.setString(1, table);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next();
            }
        }
    }

    private Connection openConnection() throws Exception {
        if (databaseFile == null) throw new IllegalStateException("training database not initialized");
        return DriverManager.getConnection("jdbc:sqlite:" + databaseFile.toAbsolutePath());
    }

    private <T> CompletableFuture<T> supplyAsync(Supplier<T> supplier) {
        return CompletableFuture.supplyAsync(supplier, ensureExecutor());
    }

    private void runSync(Supplier<Void> supplier) {
        try {
            ensureExecutor().submit(supplier::get).get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            LogUtil.warn("ml training db task failed: " + e.getMessage());
        }
    }

    private MlConfig config() {
        return ac.grim.grimac.GrimAPI.INSTANCE.getMlManager().getConfig();
    }

    public record StartResult(boolean success, String message) {}

    public record StopResult(
            boolean success,
            String message,
            int sampleCount,
            List<MlEventStore.TrainingSample> samples,
            @Nullable Path archivePath
    ) {}
}
