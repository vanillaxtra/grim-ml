package ac.grim.grimac.command.commands;

import ac.grim.grimac.GrimAPI;
import ac.grim.grimac.command.BuildableCommand;
import ac.grim.grimac.ml.LegitTrustManager;
import ac.grim.grimac.platform.api.command.PlayerSelector;
import ac.grim.grimac.platform.api.manager.cloud.CloudPlatformCommandArguments;
import ac.grim.grimac.platform.api.player.OfflinePlatformPlayer;
import ac.grim.grimac.platform.api.sender.Sender;
import ac.grim.grimac.player.GrimPlayer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.incendo.cloud.CommandManager;
import org.incendo.cloud.context.CommandContext;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;

public class GrimLegit implements BuildableCommand {

    @Override
    public void register(CommandManager<Sender> commandManager, CloudPlatformCommandArguments arguments) {
        commandManager.command(
                commandManager.commandBuilder("grim", "grimac")
                        .literal("legit")
                        .permission("grim.ml.legit")
                        .literal("list")
                        .handler(this::handleList)
        );

        commandManager.command(
                commandManager.commandBuilder("grim", "grimac")
                        .literal("legit")
                        .permission("grim.ml.legit")
                        .required("target", arguments.singlePlayerSelectorParser())
                        .handler(this::handleLegit)
        );

        commandManager.command(
                commandManager.commandBuilder("grim", "grimac")
                        .literal("unlegit")
                        .permission("grim.ml.legit")
                        .required("target", arguments.singlePlayerSelectorParser())
                        .handler(this::handleUnlegit)
        );
    }

    private void handleLegit(@NotNull CommandContext<Sender> context) {
        UUID uuid = resolveUuid(context);
        if (uuid == null) {
            context.sender().sendMessage(Component.text("player not found", NamedTextColor.RED));
            return;
        }
        if (GrimAPI.INSTANCE.getMlManager().getTrustManager().markLegit(uuid)) {
            context.sender().sendMessage(Component.text("marked player as legit", NamedTextColor.GREEN));
        } else {
            context.sender().sendMessage(Component.text("player already marked legit", NamedTextColor.YELLOW));
        }
    }

    private void handleUnlegit(@NotNull CommandContext<Sender> context) {
        UUID uuid = resolveUuid(context);
        if (uuid == null) {
            context.sender().sendMessage(Component.text("player not found", NamedTextColor.RED));
            return;
        }
        if (GrimAPI.INSTANCE.getMlManager().getTrustManager().unmarkLegit(uuid)) {
            context.sender().sendMessage(Component.text("removed legit mark", NamedTextColor.GREEN));
        } else {
            context.sender().sendMessage(Component.text("player was not manually marked legit", NamedTextColor.YELLOW));
        }
    }

    private void handleList(@NotNull CommandContext<Sender> context) {
        Sender sender = context.sender();
        LegitTrustManager trustManager = GrimAPI.INSTANCE.getMlManager().getTrustManager();
        sender.sendMessage(Component.text("manual legit players:", NamedTextColor.GOLD));
        for (UUID uuid : trustManager.manualLegitPlayers()) {
            OfflinePlatformPlayer offline = GrimAPI.INSTANCE.getPlatformPlayerFactory().getOfflineFromUUID(uuid);
            String name = offline == null ? uuid.toString() : offline.getName();
            sender.sendMessage(Component.text("- " + name + " (" + uuid + ")", NamedTextColor.GRAY));
        }
        sender.sendMessage(Component.text("online op-trusted players:", NamedTextColor.GOLD));
        for (GrimPlayer player : GrimAPI.INSTANCE.getPlayerDataManager().getEntries()) {
            if (trustManager.isOpTrusted(player) && !trustManager.isManualLegit(player.uuid)) {
                sender.sendMessage(Component.text("- " + player.user.getName(), NamedTextColor.GRAY));
            }
        }
    }

    private UUID resolveUuid(@NotNull CommandContext<Sender> context) {
        PlayerSelector target = context.get("target");
        if (target.getSinglePlayer().getPlatformPlayer() != null) {
            return target.getSinglePlayer().getPlatformPlayer().getUniqueId();
        }
        return target.getSinglePlayer().getUniqueId();
    }
}
