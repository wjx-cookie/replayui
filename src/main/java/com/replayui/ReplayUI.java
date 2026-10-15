package com.replayui;

import com.moulberry.flashback.Flashback;
import com.moulberry.flashback.action.ActionRegistry;
import com.replayui.action.UiStateAction;
import com.replayui.codec.UiStateData;
import com.replayui.log.StartupReport;
import com.replayui.net.UiStatePayload;
import com.replayui.playback.PlaybackState;
import com.replayui.playback.PlaybackTicker;
import com.replayui.record.UiSampler;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;

import java.util.List;

/**
 * Wiring only - every behaviour lives in its own module.
 *
 * <p>Two entry points because the action handler runs on the replay server and
 * sends with {@code ServerPlayNetworking}; that side needs the payload
 * registered in {@code onInitialize}, not the client one. Flashback does the
 * same thing.
 */
public class ReplayUI implements ModInitializer, ClientModInitializer {

    @Override
    public void onInitialize() {
        PayloadTypeRegistry.playS2C().register(UiStatePayload.TYPE, UiStatePayload.STREAM_CODEC);

        // Registering twice throws inside ActionRegistry, so this stays here
        // and only here. Must be the singleton: ReplayWriter keys its action
        // table by object identity.
        ActionRegistry.register(UiStateAction.INSTANCE);
    }

    @Override
    public void onInitializeClient() {
        // Client side only: it probes for Spatial GUI, which is a client mod.
        // Doing this from onInitialize could pull client classes into a context
        // where they do not belong.
        StartupReport.print();

        ClientPlayNetworking.registerGlobalReceiver(UiStatePayload.TYPE, (payload, context) -> {
            List<UiStateData> samples = payload.samples();
            // Only cache. Opening a screen here would be undone by Flashback
            // restoring the screen around payload handling.
            context.client().execute(() -> {
                if (Flashback.isInReplay()) {
                    PlaybackState.apply(samples);
                }
            });
        });

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            UiSampler.onClientTick(client);      // recording side
            PlaybackTicker.onClientTick(client); // playback side
        });
    }
}
