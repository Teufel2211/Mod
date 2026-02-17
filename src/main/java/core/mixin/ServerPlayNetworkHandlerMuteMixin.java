package core.mixin;

import core.moderation.ModerationManager;
import net.minecraft.network.packet.c2s.play.ChatMessageC2SPacket;
import net.minecraft.server.network.ServerPlayNetworkHandler;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerPlayNetworkHandler.class)
public abstract class ServerPlayNetworkHandlerMuteMixin {
    @Shadow @Final public ServerPlayerEntity player;

    @Inject(method = "onChatMessage", at = @At("HEAD"), cancellable = true)
    private void core$blockMutedChat(ChatMessageC2SPacket packet, CallbackInfo ci) {
        if (packet == null || this.player == null) return;
        if (!ModerationManager.isMuted(this.player.getUuid())) return;
        this.player.sendMessage(Text.literal("§cYou are muted."), true);
        ci.cancel();
    }
}

