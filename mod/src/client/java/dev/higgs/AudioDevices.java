package dev.higgs;

import java.util.ArrayList;
import java.util.List;
import javax.sound.sampled.*;

/** Keep the wire format independent of the formats exposed by JavaSound's Linux mixers. */
final class AudioDevices {
    record Opened<T extends DataLine>(T line, String description) {}
    interface Lines {
        Mixer.Info[] mixers();
        Line get(Mixer.Info device, DataLine.Info specification) throws LineUnavailableException;
    }
    private static final Lines SYSTEM = new Lines() {
        public Mixer.Info[] mixers() { return AudioSystem.getMixerInfo(); }
        public Line get(Mixer.Info device, DataLine.Info specification) throws LineUnavailableException {
            return device == null ? AudioSystem.getLine(specification) : AudioSystem.getMixer(device).getLine(specification);
        }
    };

    static Opened<TargetDataLine> input() throws LineUnavailableException {
        return open(TargetDataLine.class, "higgs.audio.input");
    }

    static Opened<SourceDataLine> output() throws LineUnavailableException {
        return open(SourceDataLine.class, "higgs.audio.output");
    }

    private static <T extends DataLine> Opened<T> open(Class<T> type, String property) throws LineUnavailableException {
        return open(type, property, System.getProperty(property, "").trim(), SYSTEM);
    }

    static <T extends DataLine> Opened<T> open(Class<T> type, String property, String requested, Lines lines) throws LineUnavailableException {
        List<Mixer.Info> mixers = new ArrayList<>();
        // Null means the JVM's preferred device, including javax.sound.sampled.* overrides.
        if (requested.isEmpty()) mixers.add(null);
        for (Mixer.Info info : lines.mixers()) {
            if (requested.isEmpty() || requested.equals(info.getName())) mixers.add(info);
        }
        if (mixers.isEmpty()) throw new LineUnavailableException("No mixer named '" + requested + "'; check -D" + property + ".");
        Exception last = null;
        for (Mixer.Info info : mixers) {
            for (AudioFormat format : formats()) {
                T line = null;
                try {
                    DataLine.Info specification = new DataLine.Info(type, format);
                    Line candidate = lines.get(info, specification);
                    line = type.cast(candidate);
                    int bufferBytes = (int) format.getSampleRate() / 5 * format.getFrameSize();
                    if (line instanceof TargetDataLine input) input.open(format, bufferBytes);
                    else ((SourceDataLine) line).open(format, bufferBytes);
                    String name = info == null ? "JVM default" : info.getName();
                    return new Opened<>(line, name + " / " + (int) format.getSampleRate() + "Hz " + format.getChannels() + "ch");
                } catch (LineUnavailableException | IllegalArgumentException | SecurityException error) {
                    last = error;
                    if (line != null) line.close();
                }
            }
        }
        LineUnavailableException failure = new LineUnavailableException("No usable " + (type == TargetDataLine.class ? "input" : "output")
            + " mixer; check your audio routing or -D" + property + ".");
        if (last != null) failure.initCause(last);
        throw failure;
    }

    static List<AudioFormat> formats() {
        List<AudioFormat> formats = new ArrayList<>();
        for (int rate : new int[] {24000, 48000, 44100}) {
            for (int channels : new int[] {1, 2}) formats.add(new AudioFormat(rate, 16, channels, true, false));
        }
        return formats;
    }
}
