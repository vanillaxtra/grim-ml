package ac.grim.grimac.ml;

public final class MlActivityCatalog {

    private MlActivityCatalog() {
    }

    public static String fromStableKey(String stableKey) {
        if (stableKey == null || stableKey.isEmpty()) return "misc";
        if (stableKey.startsWith("grim.prediction.") || stableKey.startsWith("grim.movement.") || stableKey.startsWith("grim.sprint.")) {
            return "movement";
        }
        if (stableKey.startsWith("grim.combat.")) return "pvp";
        if (stableKey.startsWith("grim.velocity.")) return "hit";
        if (stableKey.startsWith("grim.scaffolding.")) return "scaffold";
        if (stableKey.startsWith("grim.breaking.")) return "break";
        if (stableKey.startsWith("grim.timer.")) return "timer";
        if (stableKey.startsWith("grim.packetorder.") || stableKey.startsWith("grim.badpackets.")) return "packet";
        if (stableKey.startsWith("grim.elytra.")) return "elytra";
        if (stableKey.startsWith("grim.vehicle.")) return "vehicle";
        if (stableKey.startsWith("grim.aim.")) return "aim";
        if (stableKey.startsWith("grim.ml.activity.")) {
            return stableKey.substring("grim.ml.activity.".length());
        }
        return "misc";
    }

    public static double activityHash(String activity) {
        if (activity == null || activity.isEmpty()) return 0;
        return (activity.hashCode() & 0xFFFF) / 65535.0;
    }
}
