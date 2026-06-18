package ac.grim.grimac.ml;

import ac.grim.grimac.GrimAPI;
import ac.grim.grimac.platform.api.PlatformServer;

public final class ServerMetricsSampler {

    private static final int BUFFER_SIZE = 60;

    private final double[] tpsSamples = new double[BUFFER_SIZE];
    private final double[] msptSamples = new double[BUFFER_SIZE];
    private int sampleIndex;
    private int sampleCount;
    private long lastSampleMs;

    public void sampleIfDue(MlConfig config) {
        long now = System.currentTimeMillis();
        if (now - lastSampleMs < config.getServerMetricsIntervalMs()) return;
        lastSampleMs = now;

        PlatformServer server = GrimAPI.INSTANCE.getPlatformServer();
        double tps = server.getTPS();
        double mspt = server.getMSPT();

        tpsSamples[sampleIndex] = Double.isNaN(tps) ? 20.0 : tps;
        msptSamples[sampleIndex] = Double.isNaN(mspt) ? 50.0 : mspt;
        sampleIndex = (sampleIndex + 1) % BUFFER_SIZE;
        sampleCount = Math.min(sampleCount + 1, BUFFER_SIZE);
    }

    public double tpsAverage() {
        return average(tpsSamples);
    }

    public double tpsMin() {
        return min(tpsSamples);
    }

    public double msptAverage() {
        return average(msptSamples);
    }

    public double msptMax() {
        return max(msptSamples);
    }

    public int onlinePlayers() {
        return GrimAPI.INSTANCE.getPlatformServer().getOnlinePlayerCount();
    }

    private double average(double[] values) {
        if (sampleCount == 0) return Double.NaN;
        double sum = 0;
        for (int i = 0; i < sampleCount; i++) sum += values[i];
        return sum / sampleCount;
    }

    private double min(double[] values) {
        if (sampleCount == 0) return Double.NaN;
        double min = values[0];
        for (int i = 1; i < sampleCount; i++) min = Math.min(min, values[i]);
        return min;
    }

    private double max(double[] values) {
        if (sampleCount == 0) return Double.NaN;
        double max = values[0];
        for (int i = 1; i < sampleCount; i++) max = Math.max(max, values[i]);
        return max;
    }
}
