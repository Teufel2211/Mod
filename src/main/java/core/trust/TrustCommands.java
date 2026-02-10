package core.trust;

import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.Text;
import net.minecraft.util.math.ChunkPos;

public class TrustCommands {
    public static void register(CommandDispatcher<ServerCommandSource> dispatcher) {
        dispatcher.register(CommandManager.literal("trust")
            .then(CommandManager.argument("player", net.minecraft.command.argument.EntityArgumentType.player())
                .executes(context -> {
                    var owner = context.getSource().getPlayer();
                    var trusted = net.minecraft.command.argument.EntityArgumentType.getPlayer(context, "player");
                    if (owner != null) {
                        ChunkPos pos = new ChunkPos(owner.getBlockPos());
                        if (TrustManager.trustPlayer(owner.getUuid(), pos, trusted.getUuid())) {
                            context.getSource().sendMessage(Text.literal("Player trusted"));
                        } else {
                            context.getSource().sendMessage(Text.literal("Cannot trust"));
                        }
                    }
                    return 1;
                })));

        dispatcher.register(CommandManager.literal("untrust")
            .then(CommandManager.argument("player", net.minecraft.command.argument.EntityArgumentType.player())
                .executes(context -> {
                    var owner = context.getSource().getPlayer();
                    var trusted = net.minecraft.command.argument.EntityArgumentType.getPlayer(context, "player");
                    if (owner != null) {
                        ChunkPos pos = new ChunkPos(owner.getBlockPos());
                        if (TrustManager.untrustPlayer(owner.getUuid(), pos, trusted.getUuid())) {
                            context.getSource().sendMessage(Text.literal("Player untrusted"));
                        } else {
                            context.getSource().sendMessage(Text.literal("Cannot untrust"));
                        }
                    }
                    return 1;
                })));
    }
}