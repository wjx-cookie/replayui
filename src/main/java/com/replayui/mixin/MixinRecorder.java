package com.replayui.mixin;

import com.moulberry.flashback.record.Recorder;
import com.replayui.record.RecorderBridge;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.function.Consumer;

/**
 * Hooks Flashback's own extension point to emit a full baseline.
 *
 * <p>{@code writeCustomSnapshot} is documented for exactly this ("Mods can
 * mixin here"). It runs when the initial snapshot is written and whenever a
 * recording resumes after a pause - the moments when the per-tick delta path is
 * gated off by {@code readyToWrite()}.
 */
@Mixin(value = Recorder.class, remap = false)
public class MixinRecorder {

    /**
     * HEAD, not TAIL.
     *
     * <p>Flashback leaves {@code writeCustomSnapshot} as an empty method - the
     * whole body is one comment, so there is nothing between HEAD and TAIL.
     * HEAD is the injection point Mixin resolves most reliably on a method with
     * no instructions; a TAIL point on an empty body is the kind of thing that
     * silently does not apply. Since this is the only path that writes a
     * baseline, failing here means seeking or resuming after a pause shows no
     * UI at all - with no error anywhere, because defaultRequire is 0.
     */
    @Inject(method = "writeCustomSnapshot", at = @At("HEAD"))
    private void replayui$writeBaseline(Consumer<Packet<? super ClientGamePacketListener>> consumer,
                                        CallbackInfo ci) {
        RecorderBridge.writeBaseline(consumer);
    }
}
