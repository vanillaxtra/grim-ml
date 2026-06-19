package ac.grim.grimac.ml;

import ac.grim.grimac.api.config.ConfigManager;
import lombok.Getter;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Getter
public final class MlConfig {

    private boolean logFlags = true;
    private boolean logPassesDuringTraining = true;
    private boolean logActivitiesDuringTraining = true;
    private long sampleIntervalMs = 2000L;
    private long sampleIntervalMovementMs = 500L;
    private boolean enabled = true;
    private boolean autoTrustOp = true;
    private int minSamplesBeforeAdjust = 20;
    private double maxLenienceMultiplier = 5.0;
    private double trustedSampleWeight = 3.0;
    private int retrainIntervalMinutes = 30;
    private long serverMetricsIntervalMs = 1000L;
    private int eventRetentionDays = 90;
    private boolean baselineSamplesEnabled = true;
    private boolean baselineTrustedOnly = false;
    private long baselineSampleIntervalMs = 3000L;
    private double baselineSampleWeight = 1.0;
    private double baselineTrustedWeight = 2.0;
    private List<Integer> pingBuckets = List.of(0, 50, 100, 200, 500, 1000);
    private Map<String, Double> fallbackMultipliers = defaultFallbackMultipliers();

    public void reload(ConfigManager config) {
        enabled = config.getBooleanElse("ml.enabled", true);
        logFlags = config.getBooleanElse("ml.log-flags", true);
        logPassesDuringTraining = config.getBooleanElse("ml.log-passes-during-training", true);
        logActivitiesDuringTraining = config.getBooleanElse("ml.log-activities-during-training", true);
        sampleIntervalMs = config.getLongElse("ml.sample-interval-ms", 2000L);
        sampleIntervalMovementMs = config.getLongElse("ml.sample-interval-movement-ms", 500L);
        autoTrustOp = config.getBooleanElse("ml.auto-trust-op", true);
        minSamplesBeforeAdjust = config.getIntElse("ml.min-samples-before-adjust", 20);
        maxLenienceMultiplier = config.getDoubleElse("ml.max-lenience-multiplier", 5.0);
        trustedSampleWeight = config.getDoubleElse("ml.trusted-sample-weight", 3.0);
        retrainIntervalMinutes = config.getIntElse("ml.retrain-interval-minutes", 30);
        serverMetricsIntervalMs = config.getLongElse("ml.server-metrics-interval-ms", 1000L);
        eventRetentionDays = config.getIntElse("ml.event-retention-days", 90);
        baselineSamplesEnabled = config.getBooleanElse("ml.baseline-samples-enabled", true);
        baselineTrustedOnly = config.getBooleanElse("ml.baseline-trusted-only", false);
        baselineSampleIntervalMs = config.getLongElse("ml.baseline-sample-interval-ms", 3000L);
        baselineSampleWeight = config.getDoubleElse("ml.baseline-sample-weight", 1.0);
        baselineTrustedWeight = config.getDoubleElse("ml.baseline-trusted-weight", 2.0);

        List<Integer> buckets = parsePingBuckets(config.getListElse("ml.ping-buckets", List.of(0, 50, 100, 200, 500, 1000)));
        if (buckets.isEmpty()) {
            pingBuckets = List.of(0, 50, 100, 200, 500, 1000);
        } else {
            pingBuckets = buckets;
        }

        Map<String, Double> fallback = new LinkedHashMap<>();
        fallback.put("0-50", config.getDoubleElse("ml.fallback-multipliers.0-50", 1.0));
        fallback.put("50-100", config.getDoubleElse("ml.fallback-multipliers.50-100", 1.1));
        fallback.put("100-200", config.getDoubleElse("ml.fallback-multipliers.100-200", 1.25));
        fallback.put("200-500", config.getDoubleElse("ml.fallback-multipliers.200-500", 1.5));
        fallback.put("500+", config.getDoubleElse("ml.fallback-multipliers.500+", 2.0));
        fallbackMultipliers = fallback;
    }

    public int pingBucketIndex(int ping) {
        int safePing = Math.max(0, ping);
        int bucket = 0;
        for (int i = 0; i < pingBuckets.size(); i++) {
            if (safePing >= pingBuckets.get(i)) {
                bucket = i;
            }
        }
        return bucket;
    }

    public String pingBucketLabel(int ping) {
        int index = pingBucketIndex(ping);
        if (index >= pingBuckets.size() - 1) {
            return pingBuckets.get(pingBuckets.size() - 1) + "+";
        }
        return pingBuckets.get(index) + "-" + pingBuckets.get(index + 1);
    }

    public double fallbackMultiplier(int ping) {
        String label = pingBucketLabel(ping);
        if (label.endsWith("+")) {
            return fallbackMultipliers.getOrDefault("500+", 2.0);
        }
        return fallbackMultipliers.getOrDefault(label, 1.0);
    }

    private static Map<String, Double> defaultFallbackMultipliers() {
        Map<String, Double> fallback = new LinkedHashMap<>();
        fallback.put("0-50", 1.0);
        fallback.put("50-100", 1.1);
        fallback.put("100-200", 1.25);
        fallback.put("200-500", 1.5);
        fallback.put("500+", 2.0);
        return fallback;
    }

    private static List<Integer> parsePingBuckets(List<?> raw) {
        List<Integer> buckets = new ArrayList<>();
        for (Object value : raw) {
            if (value instanceof Number number) {
                buckets.add(number.intValue());
            }
        }
        if (buckets.isEmpty()) {
            return List.of(0, 50, 100, 200, 500, 1000);
        }
        return buckets;
    }
}
