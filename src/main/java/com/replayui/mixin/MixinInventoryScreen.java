package com.replayui.mixin;

import com.moulberry.flashback.Flashback;
import com.replayui.ReplayUIConfig;
import com.replayui.playback.PlaybackState;
import com.replayui.playback.PlaybackTicker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

import java.util.UUID;

/**
 * Draws the recorded player in a rebuilt inventory instead of the viewer.
 *
 * <p>{@code InventoryScreen} renders its little figure from
 * {@code Minecraft.player}, which during a replay is the viewer's own spectator
 * entity - so a rebuilt inventory shows the wrong person standing in it. The
 * entity is passed in as a plain argument, so swapping it is all this needs to
 * do; nothing in the data format changes, because the equipment the renderer
 * reads comes off the entity itself.
 *
 * <p><b>Why the target is {@code renderEntityInInventoryFollowsMouse}:</b> the
 * figure turns to follow the cursor, and that is the variant the screen calls.
 * The signature carries the entity as its last of ten arguments, hence
 * {@code ordinal = 9}.
 *
 * <p><b>This is best effort.</b> {@code require = 0} means that if the method
 * is ever renamed or its arity changes, Mixin silently applies nothing and the
 * mod keeps working with the old behaviour - it never fails the game at launch.
 * If the figure still shows the viewer, the target drifted and this injection
 * is the first thing to check.
 */
@Mixin(InventoryScreen.class)
public class MixinInventoryScreen {

    @ModifyVariable(
            method = "renderEntityInInventoryFollowsMouse",
            at = @At("HEAD"),
            ordinal = 9,
            argsOnly = true,
            require = 0
    )
    private static LivingEntity replayui$useRecordedPlayer(LivingEntity original) {
        // Viewer's own inventory must keep drawing the viewer.
        if (!ReplayUIConfig.showRecordedPlayer || !Flashback.isInReplay()) {
            return original;
        }
        Minecraft client = Minecraft.getInstance();
        if (client == null || !PlaybackTicker.isOurScreen(client.screen)) {
            return original;
        }

        // Camera attached to a player: that player is unambiguously the one
        // whose inventory we rebuilt, and the entity is already at hand.
        AbstractClientPlayer spectating = Flashback.getSpectatingPlayer();
        if (spectating != null) {
            return spectating;
        }

        // Free camera, the mode Flashback opens replays in. Fall back to the
        // owner of the sample on screen and look the entity up by uuid -
        // replay data stores the GameProfile, so the rebuilt entity carries
        // the uuid it was recorded with.
        UUID owner = PlaybackState.activeOwner();
        if (owner == null || client.level == null) {
            return original;
        }
        Entity found = client.level.getPlayerByUUID(owner);
        return found instanceof LivingEntity living ? living : original;
    }
}
