package ac.grim.grimac.ml;

import ac.grim.grimac.utils.anticheat.LogUtil;
import org.jetbrains.annotations.Nullable;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

public final class MlEventStore {

    private volatile @Nullable ExecutorService executor;

    private final AtomicLong approximateEventCount = new AtomicLong();

    private @Nullable Path databaseFile;
    private @Nullable Path exportFolder;

    public void init(Path dataFolder) {
        ensureExecutor();
        Path mlFolder = dataFolder.resolve("ml");
        databaseFile = mlFolder.resolve("events.sqlite");
        exportFolder = mlFolder.resolve("exports");
        try {
            Files.createDirectories(mlFolder);
        } catch (IOException e) {
            LogUtil.warn("failed to create ml folder: " + e.getMessage());
            return;
        }
        runSync(this::initDatabase);
    }

    private Void initDatabase() {
        if (databaseFile == null) return null;
        try (Connection connection = openConnection(); Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS ml_events (
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
            migrateSchema(connection);
            statement.execute("CREATE INDEX IF NOT EXISTS idx_ml_events_trusted ON ml_events(trusted)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_ml_events_check ON ml_events(check_stable_key)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_ml_events_ping ON ml_events(transaction_ping)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_ml_events_activity ON ml_events(activity)");
            approximateEventCount.set(countEvents(connection));
        } catch (Exception e) {
            LogUtil.error("failed to init ml event store", e);
        }
        return null;
    }

    private void migrateSchema(Connection connection) throws Exception {
        if (!columnExists(connection, "event_type")) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("ALTER TABLE ml_events ADD COLUMN event_type TEXT");
                statement.execute("ALTER TABLE ml_events ADD COLUMN activity TEXT");
                statement.execute("ALTER TABLE ml_events ADD COLUMN player_state_bits INTEGER DEFAULT 0");
            }
        }
    }

    private boolean columnExists(Connection connection, String column) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("PRAGMA table_info(ml_events)");
             ResultSet resultSet = statement.executeQuery()) {
            while (resultSet.next()) {
                if (column.equalsIgnoreCase(resultSet.getString("name"))) return true;
            }
        }
        return false;
    }

    public void writeAsync(MlFeatureVector features, double vl, @Nullable UUID playerUuid) {
        if (databaseFile == null) return;
        ensureExecutor().execute(() -> write(features, vl, playerUuid));
    }

    private void write(MlFeatureVector features, double vl, @Nullable UUID playerUuid) {
        if (databaseFile == null) return;
        String sql = """
                INSERT INTO ml_events (
                    occurred_epoch_ms, player_uuid, check_stable_key, check_name, event_type, activity,
                    vl, transaction_ping, tps_avg, mspt_avg, trusted, false_positive_label,
                    measured_value, config_threshold, target_multiplier, sample_weight, player_state_bits,
                    verbose_snapshot, feature_json
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            MlConfig config = ac.grim.grimac.GrimAPI.INSTANCE.getMlManager().getConfig();
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
            statement.setDouble(15, features.targetMultiplier(config));
            statement.setDouble(16, features.sampleWeight);
            statement.setInt(17, features.playerStateBits());
            statement.setString(18, features.verboseSnapshot);
            statement.setString(19, features.toJson());
            statement.executeUpdate();
            approximateEventCount.incrementAndGet();
        } catch (Exception e) {
            LogUtil.warn("failed to write ml event: " + e.getMessage());
        }
    }

    public List<TrainingSample> loadTrainingSamples(MlConfig config) {
        List<TrainingSample> samples = runSync(() -> {
            if (databaseFile == null) return List.<TrainingSample>of();
            String sql = """
                    SELECT measured_value, config_threshold, transaction_ping, tps_avg, mspt_avg,
                           false_positive_label, sample_weight, check_stable_key, activity, player_state_bits
                    FROM ml_events
                    WHERE event_type = 'FLAG' AND trusted = 1
                    """;
            try (Connection connection = openConnection();
                 PreparedStatement statement = connection.prepareStatement(sql);
                 ResultSet resultSet = statement.executeQuery()) {
                return MlTrainingSampleUtil.loadAll(resultSet, config);
            } catch (Exception e) {
                LogUtil.warn("failed to load ml training samples: " + e.getMessage());
                return List.of();
            }
        });
        return samples != null ? samples : List.of();
    }

    public CompletableFuture<List<TrainingSample>> loadTrainingSamplesAsync(MlConfig config) {
        return supplyAsync(() -> loadTrainingSamples(config));
    }

    public long approximateEventCount() {
        return approximateEventCount.get();
    }

    public int countEvents() {
        return (int) Math.min(Integer.MAX_VALUE, approximateEventCount.get());
    }

    public int countTrustedEvents() {
        Integer count = runSync(() -> {
            if (databaseFile == null) return 0;
            try (Connection connection = openConnection();
                 PreparedStatement statement = connection.prepareStatement("SELECT COUNT(*) FROM ml_events WHERE trusted = 1");
                 ResultSet resultSet = statement.executeQuery()) {
                if (resultSet.next()) return resultSet.getInt(1);
            } catch (Exception ignored) {
            }
            return 0;
        });
        return count != null ? count : 0;
    }

    public Map<String, Integer> countByActivity() {
        Map<String, Integer> counts = runSync(() -> {
            Map<String, Integer> result = new HashMap<>();
            if (databaseFile == null) return result;
            try (Connection connection = openConnection();
                 PreparedStatement statement = connection.prepareStatement(
                         "SELECT activity, COUNT(*) AS total FROM ml_events GROUP BY activity");
                 ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    String activity = resultSet.getString("activity");
                    result.put(activity == null ? "unknown" : activity, resultSet.getInt("total"));
                }
            } catch (Exception ignored) {
            }
            return result;
        });
        return counts != null ? counts : Map.of();
    }

    public void purgeOlderThanDays(int days) {
        if (databaseFile == null || days <= 0) return;
        long cutoff = System.currentTimeMillis() - days * 86_400_000L;
        ensureExecutor().execute(() -> {
            try (Connection connection = openConnection();
                 PreparedStatement statement = connection.prepareStatement("DELETE FROM ml_events WHERE occurred_epoch_ms < ?")) {
                statement.setLong(1, cutoff);
                statement.executeUpdate();
                approximateEventCount.set(countEvents(connection));
            } catch (Exception e) {
                LogUtil.warn("failed to purge ml events: " + e.getMessage());
            }
        });
    }

    public @Nullable Path exportCsv() {
        return runSync(() -> {
            if (databaseFile == null || exportFolder == null) return null;
            try {
                Files.createDirectories(exportFolder);
                Path exportFile = exportFolder.resolve("ml-events-" + System.currentTimeMillis() + ".csv");
                try (Connection connection = openConnection();
                     PreparedStatement statement = connection.prepareStatement("SELECT * FROM ml_events ORDER BY id");
                     ResultSet resultSet = statement.executeQuery();
                     BufferedWriter writer = Files.newBufferedWriter(exportFile)) {
                    writer.write("id,occurred_epoch_ms,player_uuid,check_stable_key,check_name,event_type,activity,vl,transaction_ping,tps_avg,mspt_avg,trusted,false_positive_label,measured_value,config_threshold,target_multiplier,sample_weight,player_state_bits,verbose_snapshot");
                    writer.newLine();
                    while (resultSet.next()) {
                        writer.write(resultSet.getLong("id") + ",");
                        writer.write(resultSet.getLong("occurred_epoch_ms") + ",");
                        writer.write(csv(resultSet.getString("player_uuid")) + ",");
                        writer.write(csv(resultSet.getString("check_stable_key")) + ",");
                        writer.write(csv(resultSet.getString("check_name")) + ",");
                        writer.write(csv(resultSet.getString("event_type")) + ",");
                        writer.write(csv(resultSet.getString("activity")) + ",");
                        writer.write(resultSet.getDouble("vl") + ",");
                        writer.write(resultSet.getInt("transaction_ping") + ",");
                        writer.write(resultSet.getDouble("tps_avg") + ",");
                        writer.write(resultSet.getDouble("mspt_avg") + ",");
                        writer.write(resultSet.getInt("trusted") + ",");
                        writer.write(resultSet.getInt("false_positive_label") + ",");
                        writer.write(resultSet.getDouble("measured_value") + ",");
                        writer.write(resultSet.getDouble("config_threshold") + ",");
                        writer.write(resultSet.getDouble("target_multiplier") + ",");
                        writer.write(resultSet.getDouble("sample_weight") + ",");
                        writer.write(resultSet.getInt("player_state_bits") + ",");
                        writer.write(csv(resultSet.getString("verbose_snapshot")));
                        writer.newLine();
                    }
                }
                return exportFile;
            } catch (Exception e) {
                LogUtil.warn("failed to export ml csv: " + e.getMessage());
                return null;
            }
        });
    }

    public void clearAll() {
        ensureExecutor().execute(() -> {
            if (databaseFile == null) return;
            try (Connection connection = openConnection(); Statement statement = connection.createStatement()) {
                statement.execute("DELETE FROM ml_events");
                approximateEventCount.set(0);
            } catch (Exception e) {
                LogUtil.warn("failed to clear ml events: " + e.getMessage());
            }
        });
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
                Thread thread = new Thread(r, "grim-ml-events");
                thread.setDaemon(true);
                return thread;
            });
        }
        return executor;
    }

    private int countEvents(Connection connection) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("SELECT COUNT(*) FROM ml_events");
             ResultSet resultSet = statement.executeQuery()) {
            if (resultSet.next()) return resultSet.getInt(1);
        }
        return 0;
    }

    private Connection openConnection() throws Exception {
        if (databaseFile == null) throw new IllegalStateException("ml events database not initialized");
        return DriverManager.getConnection("jdbc:sqlite:" + databaseFile.toAbsolutePath());
    }

    private <T> T runSync(Supplier<T> supplier) {
        try {
            return ensureExecutor().submit(supplier::get).get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            LogUtil.warn("ml events db task failed: " + e.getMessage());
            return null;
        }
    }

    private void runSync(Runnable runnable) {
        try {
            ensureExecutor().submit(runnable).get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            LogUtil.warn("ml events db task failed: " + e.getMessage());
        }
    }

    private <T> CompletableFuture<T> supplyAsync(Supplier<T> supplier) {
        return CompletableFuture.supplyAsync(supplier, ensureExecutor());
    }

    private static String csv(@Nullable String value) {
        if (value == null) return "";
        String escaped = value.replace("\"", "\"\"");
        if (escaped.contains(",") || escaped.contains("\"") || escaped.contains("\n")) {
            return "\"" + escaped + "\"";
        }
        return escaped;
    }

    public record TrainingSample(double[] features, double target, double weight) {}
}
