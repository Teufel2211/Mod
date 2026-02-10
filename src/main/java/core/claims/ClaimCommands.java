package core.claims;

import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.Text;
import net.minecraft.util.math.ChunkPos;

public class ClaimCommands {
    public static void register(CommandDispatcher<ServerCommandSource> dispatcher) {
        dispatcher.register(CommandManager.literal("claim")
            .executes(context -> {
                var player = context.getSource().getPlayer();
                if (player != null) {
                    ChunkPos pos = new ChunkPos(player.getBlockPos());
                    if (ClaimManager.claimChunk(player.getUuid(), pos, ClaimManager.ClaimType.PLAYER)) {
                        context.getSource().sendMessage(Text.literal("Chunk claimed"));
                    } else {
                        context.getSource().sendMessage(Text.literal("Chunk already claimed"));
                    }
                }
                return 1;
            }));

        dispatcher.register(CommandManager.literal("unclaim")
            .executes(context -> {
                var player = context.getSource().getPlayer();
                if (player != null) {
                    ChunkPos pos = new ChunkPos(player.getBlockPos());
                    if (ClaimManager.unclaimChunk(player.getUuid(), pos)) {
                        context.getSource().sendMessage(Text.literal("Chunk unclaimed"));
                    } else {
                        context.getSource().sendMessage(Text.literal("Not your claim"));
                    }
                }
                return 1;
            }));

        dispatcher.register(CommandManager.literal("claim")
            .then(CommandManager.literal("sync").executes(context -> {
                context.getSource().sendMessage(Text.literal("Claims updated. If using Xaero's map mods, restart your client to sync."));
                return 1;
            })));
    }
}