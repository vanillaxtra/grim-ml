package ac.grim.grimac.ml;

import ac.grim.grimac.checks.Check;
import ac.grim.grimac.player.GrimPlayer;
import org.jetbrains.annotations.Nullable;

public final class MlObservationLogger {

    private final MlConfig config;
    private final LegitTrustManager trustManager;
    private final ServerMetricsSampler sampler;
    private final MlEventStore eventStore;
    private final MlTrainingSessionStore trainingSessionStore;
    private final MlBaselineSampler baselineSampler;
    private final AdaptiveThresholdEngine engine;
    private final Runnable retrainCallback;

    public MlObservationLogger(
            MlConfig config,
            LegitTrustManager trustManager,
            ServerMetricsSampler sampler,
            MlEventStore eventStore,
            MlTrainingSessionStore trainingSessionStore,
            MlBaselineSampler baselineSampler,
            AdaptiveThresholdEngine engine,
            Runnable retrainCallback) {
        this.config = config;
        this.trustManager = trustManager;
        this.sampler = sampler;
        this.eventStore = eventStore;
        this.trainingSessionStore = trainingSessionStore;
        this.baselineSampler = baselineSampler;
        this.engine = engine;
        this.retrainCallback = retrainCallback;
    }

    public void logFlag(Check check, @Nullable String verboseSnapshot, double measuredValue, double configThreshold) {
        if (!config.isEnabled() || !config.isLogFlags()) return;
        GrimPlayer player = check.getPlayer();
        double measured = pickMeasured(measuredValue, check.getLastFlagMeasuredValue());
        double threshold = pickThreshold(configThreshold, check.getLastFlagConfigThreshold());
        boolean trusted = trustManager.isTrusted(player);
        MlFeatureVector features = MlFeatureVector.build(
                player,
                check,
                MlObservationType.FLAG,
                MlActivityCatalog.fromStableKey(check.getStableKey()),
                check.getViolations(),
                measured,
                threshold,
                trusted ? 1 : 0,
                trusted ? config.getTrustedSampleWeight() : 1.0,
                verboseSnapshot,
                trustManager,
                sampler,
                config
        );
        eventStore.writeAsync(features, check.getViolations(), player.uuid);
        if (trusted) {
            retrainCallback.run();
        } else if (eventStore.approximateEventCount() % config.getMinSamplesBeforeAdjust() == 0) {
            retrainCallback.run();
        }
        engine.invalidateCache();
    }

    public void logPass(Check check, String activity, double measuredValue, double configThreshold) {
        if (!config.isEnabled() || !config.isLogPassesDuringTraining()) return;
        if (!config.isBaselineSamplesEnabled()) return;
        if (!trainingSessionStore.isActive()) return;
        if (Double.isNaN(configThreshold) || configThreshold <= 0) return;

        GrimPlayer player = check.getPlayer();
        if (config.isBaselineTrustedOnly() && !trustManager.isTrusted(player)) return;
        String resolved = activity == null || activity.isEmpty()
                ? MlActivityCatalog.fromStableKey(check.getStableKey())
                : activity;
        long interval = "movement".equals(resolved)
                ? config.getSampleIntervalMovementMs()
                : config.getSampleIntervalMs();
        if (!baselineSampler.trySample(player.uuid, check.getStableKey(), resolved, interval)) {
            return;
        }

        boolean trusted = trustManager.isTrusted(player);
        double weight = trusted ? config.getBaselineTrustedWeight() : config.getBaselineSampleWeight();
        MlFeatureVector features = MlFeatureVector.build(
                player,
                check,
                MlObservationType.PASS,
                resolved,
                0,
                measuredValue,
                configThreshold,
                0,
                weight,
                "pass:" + resolved,
                trustManager,
                sampler,
                config
        );
        trainingSessionStore.writeAsync(features, 0, player.uuid);
    }

    public void logActivity(GrimPlayer player, String activity) {
        if (!config.isEnabled() || !config.isLogActivitiesDuringTraining()) return;
        if (!trainingSessionStore.isActive()) return;
        if (!baselineSampler.trySample(player.uuid, "grim.ml.activity." + activity, activity, config.getSampleIntervalMs())) {
            return;
        }
        MlFeatureVector features = MlFeatureVector.buildActivity(
                player,
                activity,
                MlObservationType.ACTIVITY,
                trustManager,
                sampler,
                config
        );
        trainingSessionStore.writeAsync(features, 0, player.uuid);
    }

    private static double pickMeasured(double primary, double fallback) {
        if (!Double.isNaN(primary)) return primary;
        if (!Double.isNaN(fallback)) return fallback;
        return Double.NaN;
    }

    private static double pickThreshold(double primary, double fallback) {
        if (!Double.isNaN(primary) && primary > 0) return primary;
        if (!Double.isNaN(fallback) && fallback > 0) return fallback;
        return Double.NaN;
    }
}
