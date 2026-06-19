package ac.grim.grimac.ml;

public final class MlFeatureJson {

    private MlFeatureJson() {
    }

    public static String toJson(MlFeatureVector features) {
        return "{"
                + "\"ping\":" + features.transactionPing
                + ",\"keepalivePing\":" + features.keepAlivePing
                + ",\"tpsAvg\":" + features.tpsAvg
                + ",\"tpsMin\":" + features.tpsMin
                + ",\"msptAvg\":" + features.msptAvg
                + ",\"msptMax\":" + features.msptMax
                + ",\"onlinePlayers\":" + features.onlinePlayers
                + ",\"clientVersion\":" + features.clientVersion
                + ",\"flying\":" + features.flying
                + ",\"inVehicle\":" + features.inVehicle
                + ",\"isSneaking\":" + features.isSneaking
                + ",\"isGliding\":" + features.isGliding
                + ",\"isSwimming\":" + features.isSwimming
                + ",\"isInBed\":" + features.isInBed
                + ",\"isDead\":" + features.isDead
                + ",\"isSprinting\":" + features.isSprinting
                + ",\"onGround\":" + features.onGround
                + ",\"riptideTicks\":" + features.riptideTicks
                + ",\"eventType\":\"" + features.eventType.name() + "\""
                + ",\"activity\":\"" + features.activity + "\""
                + ",\"trusted\":" + features.trusted
                + ",\"op\":" + features.op
                + ",\"manualLegit\":" + features.manualLegit
                + ",\"playerStateBits\":" + features.playerStateBits()
                + ",\"pingBucket\":\"" + features.pingBucketLabel + "\""
                + "}";
    }
}
