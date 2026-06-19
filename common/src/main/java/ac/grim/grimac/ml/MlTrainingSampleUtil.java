package ac.grim.grimac.ml;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

public final class MlTrainingSampleUtil {

    private MlTrainingSampleUtil() {
    }

    public static MlEventStore.TrainingSample fromRow(
            ResultSet resultSet,
            MlConfig config
    ) throws SQLException {
        double measured = resultSet.getDouble("measured_value");
        double threshold = resultSet.getDouble("config_threshold");
        int ping = resultSet.getInt("transaction_ping");
        double tps = resultSet.getDouble("tps_avg");
        double mspt = resultSet.getDouble("mspt_avg");
        int label = resultSet.getInt("false_positive_label");
        double weight = resultSet.getDouble("sample_weight");
        String stableKey = resultSet.getString("check_stable_key");
        String activity = readOptionalString(resultSet, "activity");
        int playerStateBits = readOptionalInt(resultSet, "player_state_bits");
        double target = resolveTarget(measured, threshold, ping, label, stableKey, config);
        return new MlEventStore.TrainingSample(
                new double[] {
                        ping / 1000.0,
                        sanitize(tps) / 20.0,
                        sanitize(mspt) / 50.0,
                        stableKeyHash(stableKey),
                        label,
                        MlActivityCatalog.activityHash(activity),
                        playerStateBits / 127.0
                },
                target,
                weight
        );
    }

    public static double resolveTarget(
            double measured,
            double threshold,
            int ping,
            int label,
            String stableKey,
            MlConfig config
    ) {
        if (threshold > 0 && measured > 0) {
            return Math.min(config.getMaxLenienceMultiplier(), Math.max(1.0, measured / threshold));
        }
        if (label == 1) {
            return config.fallbackMultiplier(ping);
        }
        return 1.0;
    }

    private static String readOptionalString(ResultSet resultSet, String column) {
        try {
            return resultSet.getString(column);
        } catch (SQLException ignored) {
            return null;
        }
    }

    private static int readOptionalInt(ResultSet resultSet, String column) {
        try {
            return resultSet.getInt(column);
        } catch (SQLException ignored) {
            return 0;
        }
    }

    private static double sanitize(double value) {
        return Double.isNaN(value) ? 0.0 : value;
    }

    private static double stableKeyHash(String stableKey) {
        if (stableKey == null || stableKey.isEmpty()) return 0;
        return (stableKey.hashCode() & 0xFFFF) / 65535.0;
    }

    public static List<MlEventStore.TrainingSample> loadAll(ResultSet resultSet, MlConfig config) throws SQLException {
        List<MlEventStore.TrainingSample> samples = new ArrayList<>();
        while (resultSet.next()) {
            samples.add(fromRow(resultSet, config));
        }
        return samples;
    }
}
