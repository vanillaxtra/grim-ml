package ac.grim.grimac.ml;

import ac.grim.grimac.checks.Check;
import ac.grim.grimac.player.GrimPlayer;
import com.github.retrooper.packetevents.protocol.player.GameMode;
import org.jetbrains.annotations.Nullable;

public final class MlFeatureVector {

    public final int transactionPing;
    public final int keepAlivePing;
    public final double tpsAvg;
    public final double tpsMin;
    public final double msptAvg;
    public final double msptMax;
    public final int onlinePlayers;
    public final int clientVersion;
    public final String clientBrand;
    public final GameMode gameMode;
    public final boolean flying;
    public final boolean inVehicle;
    public final boolean isSneaking;
    public final boolean isGliding;
    public final boolean isSwimming;
    public final boolean isInBed;
    public final boolean isDead;
    public final boolean isSprinting;
    public final boolean onGround;
    public final int riptideTicks;
    public final String checkStableKey;
    public final String checkName;
    public final MlObservationType eventType;
    public final String activity;
    public final double vl;
    public final double measuredValue;
    public final double configThreshold;
    public final boolean trusted;
    public final boolean op;
    public final boolean manualLegit;
    public final int falsePositiveLabel;
    public final double sampleWeight;
    public final int pingBucket;
    public final String pingBucketLabel;
    @Nullable public final String verboseSnapshot;

    private MlFeatureVector(
            int transactionPing,
            int keepAlivePing,
            double tpsAvg,
            double tpsMin,
            double msptAvg,
            double msptMax,
            int onlinePlayers,
            int clientVersion,
            String clientBrand,
            GameMode gameMode,
            boolean flying,
            boolean inVehicle,
            boolean isSneaking,
            boolean isGliding,
            boolean isSwimming,
            boolean isInBed,
            boolean isDead,
            boolean isSprinting,
            boolean onGround,
            int riptideTicks,
            String checkStableKey,
            String checkName,
            MlObservationType eventType,
            String activity,
            double vl,
            double measuredValue,
            double configThreshold,
            boolean trusted,
            boolean op,
            boolean manualLegit,
            int falsePositiveLabel,
            double sampleWeight,
            int pingBucket,
            String pingBucketLabel,
            @Nullable String verboseSnapshot) {
        this.transactionPing = transactionPing;
        this.keepAlivePing = keepAlivePing;
        this.tpsAvg = tpsAvg;
        this.tpsMin = tpsMin;
        this.msptAvg = msptAvg;
        this.msptMax = msptMax;
        this.onlinePlayers = onlinePlayers;
        this.clientVersion = clientVersion;
        this.clientBrand = clientBrand;
        this.gameMode = gameMode;
        this.flying = flying;
        this.inVehicle = inVehicle;
        this.isSneaking = isSneaking;
        this.isGliding = isGliding;
        this.isSwimming = isSwimming;
        this.isInBed = isInBed;
        this.isDead = isDead;
        this.isSprinting = isSprinting;
        this.onGround = onGround;
        this.riptideTicks = riptideTicks;
        this.checkStableKey = checkStableKey;
        this.checkName = checkName;
        this.eventType = eventType;
        this.activity = activity;
        this.vl = vl;
        this.measuredValue = measuredValue;
        this.configThreshold = configThreshold;
        this.trusted = trusted;
        this.op = op;
        this.manualLegit = manualLegit;
        this.falsePositiveLabel = falsePositiveLabel;
        this.sampleWeight = sampleWeight;
        this.pingBucket = pingBucket;
        this.pingBucketLabel = pingBucketLabel;
        this.verboseSnapshot = verboseSnapshot;
    }

    public static MlFeatureVector build(
            GrimPlayer player,
            Check check,
            MlObservationType eventType,
            String activity,
            double vl,
            double measuredValue,
            double configThreshold,
            int falsePositiveLabel,
            double sampleWeight,
            @Nullable String verboseSnapshot,
            LegitTrustManager trustManager,
            ServerMetricsSampler sampler,
            MlConfig config) {
        boolean trusted = trustManager.isTrusted(player);
        boolean manualLegit = player.uuid != null && trustManager.isManualLegit(player.uuid);
        boolean op = trustManager.isOpTrusted(player);
        int ping = player.getTransactionPing();
        String resolvedActivity = activity == null || activity.isEmpty()
                ? MlActivityCatalog.fromStableKey(check.getStableKey())
                : activity;
        return new MlFeatureVector(
                ping,
                player.getKeepAlivePing(),
                sampler.tpsAverage(),
                sampler.tpsMin(),
                sampler.msptAverage(),
                sampler.msptMax(),
                sampler.onlinePlayers(),
                player.getClientVersion().getProtocolVersion(),
                player.getBrand(),
                player.gamemode,
                player.isFlying,
                player.inVehicle(),
                player.isSneaking,
                player.isGliding,
                player.isSwimming,
                player.isInBed,
                player.compensatedEntities.self.isDead,
                player.isSprinting,
                player.onGround,
                player.riptideSpinAttackTicks,
                check.getStableKey(),
                check.getCheckName(),
                eventType,
                resolvedActivity,
                vl,
                measuredValue,
                configThreshold,
                trusted,
                op,
                manualLegit,
                falsePositiveLabel,
                sampleWeight,
                config.pingBucketIndex(ping),
                config.pingBucketLabel(ping),
                verboseSnapshot
        );
    }

    public static MlFeatureVector buildActivity(
            GrimPlayer player,
            String activity,
            MlObservationType eventType,
            LegitTrustManager trustManager,
            ServerMetricsSampler sampler,
            MlConfig config) {
        boolean trusted = trustManager.isTrusted(player);
        boolean manualLegit = player.uuid != null && trustManager.isManualLegit(player.uuid);
        boolean op = trustManager.isOpTrusted(player);
        int ping = player.getTransactionPing();
        double weight = trusted ? config.getBaselineTrustedWeight() : config.getBaselineSampleWeight();
        String stableKey = "grim.ml.activity." + activity;
        return new MlFeatureVector(
                ping,
                player.getKeepAlivePing(),
                sampler.tpsAverage(),
                sampler.tpsMin(),
                sampler.msptAverage(),
                sampler.msptMax(),
                sampler.onlinePlayers(),
                player.getClientVersion().getProtocolVersion(),
                player.getBrand(),
                player.gamemode,
                player.isFlying,
                player.inVehicle(),
                player.isSneaking,
                player.isGliding,
                player.isSwimming,
                player.isInBed,
                player.compensatedEntities.self.isDead,
                player.isSprinting,
                player.onGround,
                player.riptideSpinAttackTicks,
                stableKey,
                activity,
                eventType,
                activity,
                0,
                0,
                1.0,
                trusted,
                op,
                manualLegit,
                0,
                weight,
                config.pingBucketIndex(ping),
                config.pingBucketLabel(ping),
                eventType.name().toLowerCase() + ":" + activity
        );
    }

    public double[] modelFeatures() {
        return new double[] {
                transactionPing / 1000.0,
                sanitize(tpsAvg) / 20.0,
                sanitize(msptAvg) / 50.0,
                stableKeyHash(),
                falsePositiveLabel,
                MlActivityCatalog.activityHash(activity),
                playerStateBits() / 127.0
        };
    }

    public int playerStateBits() {
        int bits = 0;
        if (isSneaking) bits |= 1;
        if (isGliding) bits |= 2;
        if (isSwimming) bits |= 4;
        if (isInBed) bits |= 8;
        if (inVehicle) bits |= 16;
        if (isDead) bits |= 32;
        if (isSprinting) bits |= 64;
        return bits;
    }

    public double targetMultiplier(MlConfig config) {
        if (configThreshold > 0 && measuredValue > 0) {
            return Math.min(config.getMaxLenienceMultiplier(), Math.max(1.0, measuredValue / configThreshold));
        }
        return config.fallbackMultiplier(transactionPing);
    }

    public String toJson() {
        return MlFeatureJson.toJson(this);
    }

    private double stableKeyHash() {
        if (checkStableKey == null || checkStableKey.isEmpty()) return 0;
        return (checkStableKey.hashCode() & 0xFFFF) / 65535.0;
    }

    private static double sanitize(double value) {
        return Double.isNaN(value) ? 0.0 : value;
    }
}
