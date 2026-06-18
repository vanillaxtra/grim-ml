package ac.grim.grimac.command.commands;

import ac.grim.grimac.GrimAPI;
import ac.grim.grimac.command.BuildableCommand;
import ac.grim.grimac.ml.MlModelManager;
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
        MlModelManager.TrainResult result = GrimAPI.INSTANCE.getMlManager().trainNow();
        Sender sender = context.sender();
        if (result.success()) {
            sender.sendMessage(Component.text("trained model with " + result.sampleCount() + " samples", NamedTextColor.GREEN));
        } else {
            sender.sendMessage(Component.text("training failed: " + result.message() + " (" + result.sampleCount() + " samples)", NamedTextColor.RED));
        }
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
