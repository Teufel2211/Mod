package core.mixin;

import core.util.PlayerEvents;
import core.util.Safe;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.server.network.ServerPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerPlayerEntity.class)
public abstract class ServerPlayerEntityDeathMixin {
    @Inject(method = "onDeath", at = @At("HEAD"))
    private void core$onDeath(DamageSource source, CallbackInfo ci) {
        Safe.run("ServerPlayerEntityDeathMixin.onDeath", () -> PlayerEvents.onPlayerDeath((ServerPlayerEntity) (Object) this, source));
    }
}

