package ac.grim.grimac.ml;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class MlBaselineSampler {

    private final ConcurrentHashMap<String, Long> lastSampleAt = new ConcurrentHashMap<>();

    public boolean trySample(UUID uuid, String stableKey, String activity, long intervalMs) {
        if (uuid == null || stableKey == null || stableKey.isEmpty() || intervalMs <= 0) return false;
        String key = uuid + "|" + stableKey + "|" + activity;
        long now = System.currentTimeMillis();
        Long last = lastSampleAt.get(key);
        if (last != null && now - last < intervalMs) return false;
        lastSampleAt.put(key, now);
        return true;
    }

    public void clear() {
        lastSampleAt.clear();
    }
}
