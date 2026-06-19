package ac.grim.grimac.command.commands;

import ac.grim.grimac.GrimAPI;
import ac.grim.grimac.command.BuildableCommand;
import ac.grim.grimac.ml.MlModelManager;
import ac.grim.grimac.ml.MlTrainingSessionStore;
import ac.grim.grimac.platform.api.manager.cloud.CloudPlatformCommandArguments;
import ac.grim.grimac.platform.api.sender.Sender;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.incendo.cloud.CommandManager;
import org.incendo.cloud.context.CommandContext;
import org.jetbrains.annotations.NotNull;

import java.nio.file.Path;
import java.util.Map;

public class GrimMl implements BuildableCommand {

    @Override
    public void register(CommandManager<Sender> commandManager, CloudPlatformCommandArguments arguments) {
        var root = commandManager.commandBuilder("grim", "grimac")
                .literal("ml")
                .permission("grim.ml");

        commandManager.command(root.literal("status").handler(this::handleStatus));
        commandManager.command(root.literal("train").handler(this::handleTrain));

        var training = root.literal("training").permission("grim.ml.admin");
        commandManager.command(training.literal("start").handler(this::handleTrainingStart));
        commandManager.command(training.literal("stop").handler(this::handleTrainingStop));

        commandManager.command(root.literal("export").handler(this::handleExport));
        commandManager.command(
                root.literal("reset")
                        .permission("grim.ml.admin")
                        .handler(this::handleReset)
        );
    }

    private void handleStatus(@NotNull CommandContext<Sender> context) {
        Sender sender = context.sender();
        var ml = GrimAPI.INSTANCE.getMlManager();
        if (ml.getEngine() == null || ml.getModelManager() == null) {
            sender.sendMessage(Component.text("ml system not initialized yet", NamedTextColor.RED));
            return;
        }
        sender.sendMessage(Component.text("ml enabled: " + ml.getConfig().isEnabled(), NamedTextColor.GOLD));
        sender.sendMessage(Component.text("events: " + ml.getEventStore().countEvents(), NamedTextColor.GRAY));
        sender.sendMessage(Component.text("trusted events: " + ml.getEventStore().countTrustedEvents(), NamedTextColor.GRAY));
        sender.sendMessage(Component.text("training active: " + ml.getTrainingSessionStore().isActive(), NamedTextColor.GRAY));
        sender.sendMessage(Component.text("training samples: " + ml.getTrainingSessionStore().savedSampleCount(), NamedTextColor.GRAY));
        Map<String, Integer> activityCounts = ml.trainingActivityCounts();
        if (!activityCounts.isEmpty()) {
            sender.sendMessage(Component.text("training activity breakdown:", NamedTextColor.GOLD));
            activityCounts.forEach((activity, count) ->
                    sender.sendMessage(Component.text("  " + activity + ": " + count, NamedTextColor.GRAY)));
        }
        sender.sendMessage(Component.text("model loaded: " + ml.getModelManager().hasModel(), NamedTextColor.GRAY));
        sender.sendMessage(Component.text("trained samples: " + ml.getModelManager().trainedSampleCount(), NamedTextColor.GRAY));
        sender.sendMessage(Component.text("min samples: " + ml.getConfig().getMinSamplesBeforeAdjust(), NamedTextColor.GRAY));
        Map<String, Double> cache = ml.getEngine().snapshotCache();
        if (cache.isEmpty()) {
            sender.sendMessage(Component.text("no cached multipliers yet", NamedTextColor.DARK_GRAY));
            return;
        }
        sender.sendMessage(Component.text("cached multipliers:", NamedTextColor.GOLD));
        cache.forEach((key, value) -> sender.sendMessage(Component.text(key + " = " + String.format("%.3f", value), NamedTextColor.GRAY)));
    }

    private void handleTrain(@NotNull CommandContext<Sender> context) {
        Sender sender = context.sender();
        sender.sendMessage(Component.text("training model...", NamedTextColor.GRAY));
        GrimAPI.INSTANCE.getScheduler().getAsyncScheduler().runNow(GrimAPI.INSTANCE.getGrimPlugin(), () -> {
            MlModelManager.TrainResult result = GrimAPI.INSTANCE.getMlManager().trainNowAsync().join();
            if (result.success()) {
                sender.sendMessage(Component.text("trained model with " + result.sampleCount() + " samples", NamedTextColor.GREEN));
            } else {
                sender.sendMessage(Component.text("training failed: " + result.message() + " (" + result.sampleCount() + " samples)", NamedTextColor.RED));
            }
        });
    }

    private void handleTrainingStart(@NotNull CommandContext<Sender> context) {
        Sender sender = context.sender();
        GrimAPI.INSTANCE.getScheduler().getAsyncScheduler().runNow(GrimAPI.INSTANCE.getGrimPlugin(), () -> {
            MlTrainingSessionStore.StartResult result = GrimAPI.INSTANCE.getMlManager().startTrainingAsync().join();
            if (result.success()) {
                sender.sendMessage(Component.text("training started, collecting legit gameplay samples", NamedTextColor.GREEN));
                sender.sendMessage(Component.text("session: " + result.message(), NamedTextColor.GRAY));
            } else {
                sender.sendMessage(Component.text("failed to start training: " + result.message(), NamedTextColor.RED));
            }
        });
    }

    private void handleTrainingStop(@NotNull CommandContext<Sender> context) {
        Sender sender = context.sender();
        sender.sendMessage(Component.text("stopping training and saving samples...", NamedTextColor.GRAY));
        GrimAPI.INSTANCE.getScheduler().getAsyncScheduler().runNow(GrimAPI.INSTANCE.getGrimPlugin(), () -> {
            MlModelManager.TrainResult result = GrimAPI.INSTANCE.getMlManager().stopTrainingAsync().join();
            var session = GrimAPI.INSTANCE.getMlManager().getTrainingSessionStore();
            if (result.success()) {
                Map<String, Integer> activityCounts = GrimAPI.INSTANCE.getMlManager().trainingActivityCounts();
                sender.sendMessage(Component.text("training stopped, saved " + session.savedSampleCount() + " samples and trained model", NamedTextColor.GREEN));
                if (!activityCounts.isEmpty()) {
                    sender.sendMessage(Component.text("activity breakdown:", NamedTextColor.GOLD));
                    activityCounts.forEach((activity, count) ->
                            sender.sendMessage(Component.text("  " + activity + ": " + count, NamedTextColor.GRAY)));
                }
            } else {
                sender.sendMessage(Component.text("training stop failed: " + result.message() + " (" + result.sampleCount() + " samples)", NamedTextColor.RED));
            }
        });
    }

    private void handleExport(@NotNull CommandContext<Sender> context) {
        Path export = GrimAPI.INSTANCE.getMlManager().getEventStore().exportCsv();
        Sender sender = context.sender();
        if (export == null) {
            sender.sendMessage(Component.text("export failed", NamedTextColor.RED));
            return;
        }
        sender.sendMessage(Component.text("exported to " + export.toAbsolutePath(), NamedTextColor.GREEN));
    }

    private void handleReset(@NotNull CommandContext<Sender> context) {
        GrimAPI.INSTANCE.getMlManager().resetModel();
        context.sender().sendMessage(Component.text("ml model and events reset", NamedTextColor.GREEN));
    }
}
