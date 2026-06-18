package ac.grim.grimac.ml;

import ac.grim.grimac.utils.anticheat.LogUtil;
import smile.data.DataFrame;
import smile.data.Tuple;
import smile.data.formula.Formula;
import smile.data.type.StructField;
import smile.data.type.StructType;
import smile.data.vector.DoubleVector;
import smile.data.type.DataTypes;
import smile.regression.GradientTreeBoost;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public final class MlModelManager {

    private static final StructType PREDICTOR_SCHEMA = new StructType(
            new StructField("ping", DataTypes.DoubleType),
            new StructField("tps", DataTypes.DoubleType),
            new StructField("mspt", DataTypes.DoubleType),
            new StructField("check", DataTypes.DoubleType),
            new StructField("label", DataTypes.DoubleType)
    );

    private final MlConfig config;
    private final Path modelFile;
    private volatile @Nullable GradientTreeBoost model;
    private volatile int trainedSampleCount;

    public MlModelManager(MlConfig config, Path modelFile) {
        this.config = config;
        this.modelFile = modelFile;
    }

    public void loadModel() {
        if (!Files.exists(modelFile)) return;
        try (ObjectInputStream input = new ObjectInputStream(Files.newInputStream(modelFile))) {
            Object loaded = input.readObject();
            if (loaded instanceof GradientTreeBoost boost) {
                model = boost;
            }
        } catch (Exception e) {
            LogUtil.warn("failed to load ml model: " + e.getMessage());
        }
    }

    public synchronized TrainResult train(List<MlEventStore.TrainingSample> samples) {
        if (samples.size() < config.getMinSamplesBeforeAdjust()) {
            return new TrainResult(false, samples.size(), "not enough samples");
        }

        try {
            int n = samples.size();
            double[][] x = new double[n][];
            double[] y = new double[n];
            for (int i = 0; i < n; i++) {
                x[i] = samples.get(i).features();
                y[i] = samples.get(i).target();
            }

            DataFrame frame = DataFrame.of(x, "ping", "tps", "mspt", "check", "label")
                    .merge(DoubleVector.of("target", y));
            GradientTreeBoost trained = GradientTreeBoost.fit(Formula.lhs("target"), frame);
            this.model = trained;
            this.trainedSampleCount = samples.size();
            saveModel(trained);
            return new TrainResult(true, samples.size(), "trained");
        } catch (Exception e) {
            LogUtil.warn("ml training failed: " + e.getMessage());
            return new TrainResult(false, samples.size(), e.getMessage());
        }
    }

    public double predict(MlFeatureVector features) {
        GradientTreeBoost current = model;
        if (current == null) {
            return config.fallbackMultiplier(features.transactionPing);
        }
        try {
            return clamp(current.predict(Tuple.of(features.modelFeatures(), PREDICTOR_SCHEMA)));
        } catch (Exception e) {
            return config.fallbackMultiplier(features.transactionPing);
        }
    }

    public double predictFromContext(int ping, double tps, double mspt, String stableKey, boolean trusted) {
        double[] features = new double[] {
                ping / 1000.0,
                sanitize(tps) / 20.0,
                sanitize(mspt) / 50.0,
                stableKeyHash(stableKey),
                trusted ? 1.0 : 0.0
        };
        GradientTreeBoost current = model;
        if (current == null) return config.fallbackMultiplier(ping);
        try {
            return clamp(current.predict(Tuple.of(features, PREDICTOR_SCHEMA)));
        } catch (Exception e) {
            return config.fallbackMultiplier(ping);
        }
    }

    public void reset() {
        model = null;
        trainedSampleCount = 0;
        try {
            Files.deleteIfExists(modelFile);
        } catch (IOException ignored) {
        }
    }

    public boolean hasModel() {
        return model != null;
    }

    public int trainedSampleCount() {
        return trainedSampleCount;
    }

    private void saveModel(GradientTreeBoost model) {
        try {
            Files.createDirectories(modelFile.getParent());
            try (ObjectOutputStream output = new ObjectOutputStream(Files.newOutputStream(modelFile))) {
                output.writeObject(model);
            }
        } catch (IOException e) {
            LogUtil.warn("failed to save ml model: " + e.getMessage());
        }
    }

    private double clamp(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) return 1.0;
        return Math.max(1.0, Math.min(config.getMaxLenienceMultiplier(), value));
    }

    private static double sanitize(double value) {
        return Double.isNaN(value) ? 0.0 : value;
    }

    private static double stableKeyHash(String stableKey) {
        if (stableKey == null || stableKey.isEmpty()) return 0;
        return (stableKey.hashCode() & 0xFFFF) / 65535.0;
    }

    public record TrainResult(boolean success, int sampleCount, String message) {}
}
