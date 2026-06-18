package ac.grim.grimac.ml;

import ac.grim.grimac.player.GrimPlayer;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class AdaptiveThresholdEngine {

    private final MlConfig config;
    private final MlModelManager modelManager;
    private final ServerMetricsSampler sampler;
    private final Map<String, Double> cache = new ConcurrentHashMap<>();

    public AdaptiveThresholdEngine(MlConfig config, MlModelManager modelManager, ServerMetricsSampler sampler) {
        this.config = config;
        this.modelManager = modelManager;
        this.sampler = sampler;
    }

    public double getMultiplier(GrimPlayer player, String checkStableKey, String paramKey) {
        if (!config.isEnabled()) return 1.0;
        if (player == null) return 1.0;

        int ping = player.getTransactionPing();
        String cacheKey = checkStableKey + ":" + paramKey + ":" + config.pingBucketIndex(ping);
        Double cached = cache.get(cacheKey);
        if (cached != null) return cached;

        double multiplier = modelManager.predictFromContext(
                ping,
                sampler.tpsAverage(),
                sampler.msptAverage(),
                checkStableKey,
                false
        );
        cache.put(cacheKey, multiplier);
        return multiplier;
    }

    public void invalidateCache() {
        cache.clear();
    }

    public Map<String, Double> snapshotCache() {
        return Map.copyOf(cache);
    }
}
