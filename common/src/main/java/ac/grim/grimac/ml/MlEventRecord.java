package ac.grim.grimac.ml;

import org.jetbrains.annotations.Nullable;

import java.util.UUID;

public record MlEventRecord(
        long id,
        long occurredEpochMs,
        @Nullable UUID playerUuid,
        String checkStableKey,
        String checkName,
        double vl,
        int transactionPing,
        double tpsAvg,
        double msptAvg,
        boolean trusted,
        int falsePositiveLabel,
        double measuredValue,
        double configThreshold,
        double targetMultiplier,
        double sampleWeight,
        @Nullable String verboseSnapshot,
        String featureJson) {
}
