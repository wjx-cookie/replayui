package com.replayui.mixin;

import com.moulberry.flashback.Flashback;
import com.replayui.ReplayUIConfig;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Optional: makes the viewer report the recorded player's yaw.
 *
 * <p>Spatial GUI positions its floating panel from
 * {@code Minecraft.getInstance().player.getYRot()}. In a replay that player is
 * the viewer (a spectator entity of its own), so the panel would otherwise be
 * pinned to the camera instead of standing in front of whoever was recorded.
 *
 * <p>Disabled unless {@link ReplayUIConfig#syncYaw} is on - this one still
 * needs a real session to confirm it looks right.
 */
@Mixin(Entity.class)
public class MixinLocalPlayerYaw {

    /**
     * Targets {@code Entity}, not {@code LocalPlayer}.
     *
     * <p>Mixin works on bytecode: {@code getYRot()} is declared on Entity and
     * LocalPlayer does not override it, so a mixin targeting LocalPlayer finds
     * no such method. With {@code require = 0} that fails silently and this
     * feature would simply never run. Injecting the declaring class and
     * filtering with instanceof is the documented way to do this.
     */
    @Inject(method = "getYRot", at = @At("HEAD"), cancellable = true, require = 0)
    private void replayui$spectatingYaw(CallbackInfoReturnable<Float> cir) {
        // Cheapest check first: this runs for every entity, every frame.
        if (!ReplayUIConfig.syncYaw || !Flashback.isInReplay()) {
            return;
        }
        if (!((Object) this instanceof LocalPlayer)) {
            return;
        }
        AbstractClientPlayer spectating = Flashback.getSpectatingPlayer();
        if (spectating != null) {
            cir.setReturnValue(spectating.getYRot());
        }
    }
}
