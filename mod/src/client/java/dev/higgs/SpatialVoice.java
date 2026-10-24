package dev.higgs;

import java.nio.ByteBuffer;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import javax.sound.sampled.AudioFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.Sound;
import net.minecraft.client.sounds.AudioStream;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.client.sounds.WeighedSoundEvents;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.util.valueproviders.ConstantFloat;
import net.minecraft.world.entity.Entity;

/** All lifecycle operations run on the client thread; stream reads run on Minecraft's audio thread. */
public final class SpatialVoice extends AbstractTickableSoundInstance implements AudioStream {
    private static final ConcurrentHashMap<Identifier, SpatialVoice> STREAMS = new ConcurrentHashMap<>();
    private final Entity villager;
    private final VoicePcmBuffer pcm = new VoicePcmBuffer();

    SpatialVoice(Entity villager) {
        super(SoundEvent.createVariableRangeEvent(Identifier.fromNamespaceAndPath("higgs_villagers", "voice/" + UUID.randomUUID())),
            SoundSource.NEUTRAL, RandomSource.create());
        this.villager = villager;
        sound = new Sound(identifier, ConstantFloat.of(1), ConstantFloat.of(1), 1, Sound.Type.FILE, true, false, 16);
        attenuation = Attenuation.LINEAR;
        relative = false;
        volume = pitch = 1;
        updatePosition();
        STREAMS.put(sound.getPath(), this);
    }
    public static AudioStream stream(Identifier path) { return STREAMS.get(path); }
    @Override public WeighedSoundEvents resolve(SoundManager manager) {
        WeighedSoundEvents event = new WeighedSoundEvents(identifier, null);
        event.addSound(sound);
        return event;
    }
    boolean offer(byte[] bytes) { return pcm.offer(bytes); }
    int queuedBytes() { return pcm.queuedBytes(); }
    boolean closed() { return pcm.isClosed(); }
    @Override public boolean canStartSilent() { return true; }
    @Override public void tick() {
        if (closed() || villager == null || villager.isRemoved() || Minecraft.getInstance().level != villager.level()) {
            close();
            stop();
        } else updatePosition();
    }
    private void updatePosition() {
        if (villager != null) { x = villager.getX(); y = villager.getEyeY(); z = villager.getZ(); }
    }
    @Override public AudioFormat getFormat() { return new AudioFormat(24000, 16, 1, true, false); }
    @Override public ByteBuffer read(int bytes) { return pcm.read(bytes); }
    @Override public void close() { pcm.close(); STREAMS.remove(sound.getPath(), this); }
    void cancel() { close(); stop(); Minecraft.getInstance().getSoundManager().stop(this); }
}
