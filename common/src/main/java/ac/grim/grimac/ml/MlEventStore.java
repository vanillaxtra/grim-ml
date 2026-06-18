package ac.grim.grimac.ml;

import ac.grim.grimac.GrimAPI;
import ac.grim.grimac.utils.anticheat.LogUtil;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.jetbrains.annotations.Nullable;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MlEventStore {

    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "grim-ml-events");
        thread.setDaemon(true);
        return thread;
    });

    private @Nullable HikariDataSource dataSource;
    private Path databaseFile;
    private Path exportFolder;

    public void init(Path dataFolder) {
        shutdown();
        Path mlFolder = dataFolder.resolve("ml");
        databaseFile = mlFolder.resolve("events.sqlite");
        exportFolder = mlFolder.resolve("exports");
        try {
            Files.createDirectories(mlFolder);
        } catch (IOException e) {
            LogUtil.warn("failed to create ml folder: " + e.getMessage());
            return;
        }

        HikariConfig config = new HikariConfig();
        config.setJdbcUrl("jdbc:sqlite:" + databaseFile.toAbsolutePath());
        config.setMaximumPoolSize(1);
        config.setPoolName("grim-ml-events");
        dataSource = new HikariDataSource(config);
        createSchema();
    }

    private void createSchema() {
        if (dataSource == null) return;
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS ml_events (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        occurred_epoch_ms INTEGER NOT NULL,
                        player_uuid TEXT,
                        check_stable_key TEXT,
                        check_name TEXT,
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
                        verbose_snapshot TEXT,
                        feature_json TEXT
                    )
                    """);
            statement.execute("CREATE INDEX IF NOT EXISTS idx_ml_events_trusted ON ml_events(trusted)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_ml_events_check ON ml_events(check_stable_key)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_ml_events_ping ON ml_events(transaction_ping)");
        } catch (Exception e) {
            LogUtil.error("failed to init ml event store", e);
        }
    }

    public void writeAsync(MlFeatureVector features, double vl, @Nullable UUID playerUuid) {
        if (dataSource == null) return;
        executor.execute(() -> write(features, vl, playerUuid));
    }

    private void write(MlFeatureVector features, double vl, @Nullable UUID playerUuid) {
        if (dataSource == null) return;
        String sql = """
                INSERT INTO ml_events (
                    occurred_epoch_ms, player_uuid, check_stable_key, check_name, vl,
                    transaction_ping, tps_avg, mspt_avg, trusted, false_positive_label,
                    measured_value, config_threshold, target_multiplier, sample_weight,
                    verbose_snapshot, feature_json
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, System.currentTimeMillis());
            statement.setString(2, playerUuid == null ? null : playerUuid.toString());
            statement.setString(3, features.checkStableKey);
            statement.setString(4, features.checkName);
            statement.setDouble(5, vl);
            statement.setInt(6, features.transactionPing);
            statement.setDouble(7, features.tpsAvg);
            statement.setDouble(8, features.msptAvg);
            statement.setInt(9, features.trusted ? 1 : 0);
            statement.setInt(10, features.falsePositiveLabel);
            statement.setDouble(11, features.measuredValue);
            statement.setDouble(12, features.configThreshold);
            statement.setDouble(13, features.targetMultiplier(GrimAPI.INSTANCE.getMlManager().getConfig()));
            statement.setDouble(14, features.sampleWeight);
            statement.setString(15, features.verboseSnapshot);
            statement.setString(16, toJson(features));
            statement.executeUpdate();
        } catch (Exception e) {
            LogUtil.warn("failed to write ml event: " + e.getMessage());
        }
    }

    public List<TrainingSample> loadTrainingSamples() {
        List<TrainingSample> samples = new ArrayList<>();
        if (dataSource == null) return samples;
        String sql = "SELECT measured_value, config_threshold, transaction_ping, tps_avg, mspt_avg, false_positive_label, sample_weight, check_stable_key FROM ml_events";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet resultSet = statement.executeQuery()) {
            while (resultSet.next()) {
                double measured = resultSet.getDouble("measured_value");
                double threshold = resultSet.getDouble("config_threshold");
                int ping = resultSet.getInt("transaction_ping");
                double tps = resultSet.getDouble("tps_avg");
                double mspt = resultSet.getDouble("mspt_avg");
                int label = resultSet.getInt("false_positive_label");
                double weight = resultSet.getDouble("sample_weight");
                String stableKey = resultSet.getString("check_stable_key");
                double target;
                if (threshold > 0 && measured > 0) {
                    target = Math.min(GrimAPI.INSTANCE.getMlManager().getConfig().getMaxLenienceMultiplier(),
                            Math.max(1.0, measured / threshold));
                } else if (label == 1) {
                    target = GrimAPI.INSTANCE.getMlManager().getConfig().fallbackMultiplier(ping);
                } else {
                    target = 1.0;
                }
                samples.add(new TrainingSample(
                        new double[] {
                                ping / 1000.0,
                                sanitize(tps) / 20.0,
                                sanitize(mspt) / 50.0,
                                stableKeyHash(stableKey),
                                label
                        },
                        target,
                        weight
                ));
            }
        } catch (Exception e) {
            LogUtil.warn("failed to load ml training samples: " + e.getMessage());
        }
        return samples;
    }

    public int countEvents() {
        if (dataSource == null) return 0;
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("SELECT COUNT(*) FROM ml_events");
             ResultSet resultSet = statement.executeQuery()) {
            if (resultSet.next()) return resultSet.getInt(1);
        } catch (Exception ignored) {
        }
        return 0;
    }

    public int countTrustedEvents() {
        if (dataSource == null) return 0;
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("SELECT COUNT(*) FROM ml_events WHERE trusted = 1");
             ResultSet resultSet = statement.executeQuery()) {
            if (resultSet.next()) return resultSet.getInt(1);
        } catch (Exception ignored) {
        }
        return 0;
    }

    public void purgeOlderThanDays(int days) {
        if (dataSource == null || days <= 0) return;
        long cutoff = System.currentTimeMillis() - days * 86_400_000L;
        executor.execute(() -> {
            try (Connection connection = dataSource.getConnection();
                 PreparedStatement statement = connection.prepareStatement("DELETE FROM ml_events WHERE occurred_epoch_ms < ?")) {
                statement.setLong(1, cutoff);
                statement.executeUpdate();
            } catch (Exception e) {
                LogUtil.warn("failed to purge ml events: " + e.getMessage());
            }
        });
    }

    public @Nullable Path exportCsv() {
        if (dataSource == null) return null;
        try {
            Files.createDirectories(exportFolder);
            Path exportFile = exportFolder.resolve("ml-events-" + System.currentTimeMillis() + ".csv");
            try (Connection connection = dataSource.getConnection();
                 PreparedStatement statement = connection.prepareStatement("SELECT * FROM ml_events ORDER BY id");
                 ResultSet resultSet = statement.executeQuery();
                 BufferedWriter writer = Files.newBufferedWriter(exportFile)) {
                writer.write("id,occurred_epoch_ms,player_uuid,check_stable_key,check_name,vl,transaction_ping,tps_avg,mspt_avg,trusted,false_positive_label,measured_value,config_threshold,target_multiplier,sample_weight,verbose_snapshot");
                writer.newLine();
                while (resultSet.next()) {
                    writer.write(resultSet.getLong("id") + ",");
                    writer.write(resultSet.getLong("occurred_epoch_ms") + ",");
                    writer.write(csv(resultSet.getString("player_uuid")) + ",");
                    writer.write(csv(resultSet.getString("check_stable_key")) + ",");
                    writer.write(csv(resultSet.getString("check_name")) + ",");
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
                    writer.write(csv(resultSet.getString("verbose_snapshot")));
                    writer.newLine();
                }
            }
            return exportFile;
        } catch (Exception e) {
            LogUtil.warn("failed to export ml csv: " + e.getMessage());
            return null;
        }
    }

    public void clearAll() {
        if (dataSource == null) return;
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("DELETE FROM ml_events");
        } catch (Exception e) {
            LogUtil.warn("failed to clear ml events: " + e.getMessage());
        }
    }

    public void shutdown() {
        if (dataSource != null) {
            dataSource.close();
            dataSource = null;
        }
    }

    private static String csv(@Nullable String value) {
        if (value == null) return "";
        String escaped = value.replace("\"", "\"\"");
        if (escaped.contains(",") || escaped.contains("\"") || escaped.contains("\n")) {
            return "\"" + escaped + "\"";
        }
        return escaped;
    }

    private static String toJson(MlFeatureVector features) {
        return "{"
                + "\"ping\":" + features.transactionPing
                + ",\"keepalivePing\":" + features.keepAlivePing
                + ",\"tpsAvg\":" + features.tpsAvg
                + ",\"tpsMin\":" + features.tpsMin
                + ",\"msptAvg\":" + features.msptAvg
                + ",\"msptMax\":" + features.msptMax
                + ",\"onlinePlayers\":" + features.onlinePlayers
                + ",\"clientVersion\":" + features.clientVersion
                + ",\"flying\":" + features.flying
                + ",\"inVehicle\":" + features.inVehicle
                + ",\"trusted\":" + features.trusted
                + ",\"op\":" + features.op
                + ",\"manualLegit\":" + features.manualLegit
                + ",\"pingBucket\":\"" + features.pingBucketLabel + "\""
                + "}";
    }

    private static double sanitize(double value) {
        return Double.isNaN(value) ? 0.0 : value;
    }

    private static double stableKeyHash(String stableKey) {
        if (stableKey == null || stableKey.isEmpty()) return 0;
        return (stableKey.hashCode() & 0xFFFF) / 65535.0;
    }

    public record TrainingSample(double[] features, double target, double weight) {}
}
