package ac.grim.grimac.ml;

import ac.grim.grimac.checks.Check;
import ac.grim.grimac.player.GrimPlayer;
import org.jetbrains.annotations.Nullable;

public final class MlFlagListener {

    private final MlConfig config;
    private final LegitTrustManager trustManager;
    private final ServerMetricsSampler sampler;
    private final MlEventStore eventStore;
    private final MlModelManager modelManager;
    private final AdaptiveThresholdEngine engine;
    private final Runnable retrainCallback;

    public MlFlagListener(
            MlConfig config,
            LegitTrustManager trustManager,
            ServerMetricsSampler sampler,
            MlEventStore eventStore,
            MlModelManager modelManager,
            AdaptiveThresholdEngine engine,
            Runnable retrainCallback) {
        this.config = config;
        this.trustManager = trustManager;
        this.sampler = sampler;
        this.eventStore = eventStore;
        this.modelManager = modelManager;
        this.engine = engine;
        this.retrainCallback = retrainCallback;
    }

    public void onFlag(Check check, @Nullable String verboseSnapshot) {
        if (!config.isEnabled()) return;
        GrimPlayer player = check.getPlayer();
        MlFeatureVector features = MlFeatureVector.fromFlag(
                player,
                check,
                check.getViolations(),
                verboseSnapshot,
                check.getLastFlagMeasuredValue(),
                check.getLastFlagConfigThreshold(),
                trustManager,
                sampler,
                config
        );
        eventStore.writeAsync(features, check.getViolations(), player.uuid);
        if (features.trusted) {
            retrainCallback.run();
        } else if (eventStore.countEvents() % config.getMinSamplesBeforeAdjust() == 0) {
            retrainCallback.run();
        }
        engine.invalidateCache();
    }
}
