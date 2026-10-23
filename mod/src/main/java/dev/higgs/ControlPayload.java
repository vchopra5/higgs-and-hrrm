package dev.higgs;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** Small control messages only. Audio travels directly to the authenticated bridge. */
public record ControlPayload(String json) implements CustomPacketPayload {
    public static final Type<ControlPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath("higgs_villagers", "control"));
    public static final StreamCodec<RegistryFriendlyByteBuf, ControlPayload> STREAM_CODEC = StreamCodec.of(
        (buffer, payload) -> buffer.writeUtf(payload.json(), 32767),
        buffer -> new ControlPayload(buffer.readUtf(32767)));
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
