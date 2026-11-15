package com.replayui.mixin;

import com.moulberry.flashback.Flashback;
import com.replayui.ReplayUIConfig;
import com.replayui.codec.UiStateData;
import com.replayui.log.DebugLog;
import com.replayui.playback.PlaybackState;
import com.replayui.playback.PlaybackTicker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
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
 * <p><b>Why {@code ordinal = 0}:</b> {@code renderEntityInInventoryFollowsMouse}
 * takes ten arguments and the {@code LivingEntity} is the tenth, but Mixin's
 * {@code ordinal} is the index <i>among variables of the same type</i>, not the
 * argument slot. There is exactly one {@code LivingEntity} argument, so its
 * ordinal is 0. The old value 9 matched nothing and, with {@code require = 0},
 * silently applied nothing - which is exactly why the figure never appeared in
 * a real game even though everything compiled.
 *
 * <p>{@code require = 0} is kept so a mapping drift on a future version never
 * fails the game at launch. The verbose log line makes a miss visible: with
 * {@code -Dreplayui.debug=true} there must be "inventory figure hook entered"
 * whenever a rebuilt inventory is drawn.
 */
@Mixin(InventoryScreen.class)
public class MixinInventoryScreen {

    @ModifyVariable(
            method = "renderEntityInInventoryFollowsMouse",
            at = @At("HEAD"),
            ordinal = 0,
            argsOnly = true,
            require = 0
    )
    private static LivingEntity replayui$useRecordedPlayer(LivingEntity original) {
        DebugLog.verbose("inventory figure hook entered");
        if (!ReplayUIConfig.showRecordedPlayer || !Flashback.isInReplay()) {
            return original;
        }
        Minecraft client = Minecraft.getInstance();
        if (client == null || !replayui$belongsToReplay(client.screen)) {
            return original;
        }

        // Camera attached to a player: that player is unambiguously the one
        // whose inventory we rebuilt, and the entity is already at hand.
        AbstractClientPlayer spectating = Flashback.getSpectatingPlayer();
        if (spectating != null) {
            DebugLog.verbose("inventory figure: spectating " + spectating.getUUID());
            return spectating;
        }

        // Free camera, the mode Flashback opens replays in. Read the sample that
        // is actually on screen rather than the cached field, so the figure and
        // the interface can never disagree about the owner.
        UiStateData sample = PlaybackState.current();
        UUID owner = sample != null ? sample.ownerUuid() : PlaybackState.activeOwner();
        if (owner == null || client.level == null) {
            DebugLog.verbose("inventory figure: no owner uuid, keeping viewer");
            return original;
        }
        Entity found = client.level.getPlayerByUUID(owner);
        DebugLog.verbose("inventory figure: owner=" + owner
                + " entity=" + (found == null ? "null" : found.getClass().getSimpleName()));
        return found instanceof LivingEntity living ? living : original;
    }

    /**
     * The export hook re-attaches the rebuilt screen through a fresh call, so
     * the instance can differ from the one PlaybackTicker remembers. In a replay
     * an inventory screen with live UI data is ours either way; outside a replay
     * (or for the viewer's own inventory) the original entity is kept.
     */
    private static boolean replayui$belongsToReplay(Screen screen) {
        if (PlaybackTicker.isOurScreen(screen)) {
            return true;
        }
        if (screen instanceof InventoryScreen) {
            UiStateData sample = PlaybackState.current();
            return sample != null && sample.open();
        }
        return false;
    }
}
