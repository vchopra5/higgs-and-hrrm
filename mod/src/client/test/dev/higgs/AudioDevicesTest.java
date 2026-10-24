package dev.higgs;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import javax.sound.sampled.*;

public final class AudioDevicesTest {
    public static void main(String[] args) throws Exception {
        Mixer.Info hardware = new Mixer.Info("PipeWire test input", "test", "test", "1") {};
        List<String> attempted = new ArrayList<>();
        int[] closed = {0};
        AudioDevices.Lines lines = new AudioDevices.Lines() {
            public Mixer.Info[] mixers() { return new Mixer.Info[] {hardware}; }
            public Line get(Mixer.Info device, DataLine.Info specification) {
                AudioFormat format = specification.getFormats()[0];
                attempted.add((device == null ? "default" : device.getName()) + ":" + format.getSampleRate() + ":" + format.getChannels());
                return fakeLine(format, device != null && format.getSampleRate() == 48000 && format.getChannels() == 2, closed);
            }
        };
        var fallback = AudioDevices.open(TargetDataLine.class, "higgs.audio.input", "", lines);
        check(fallback.line().getFormat().getSampleRate() == 48000 && fallback.line().getFormat().getChannels() == 2, "hardware fallback");
        check(attempted.getFirst().equals("default:24000.0:1"), "default wire format first");
        check(closed[0] == attempted.size() - 1, "every failed line closed");
        check(fallback.description().contains("PipeWire test input"), "selected mixer diagnostic");
        attempted.clear();
        AudioDevices.open(TargetDataLine.class, "higgs.audio.input", hardware.getName(), lines).line().close();
        check(attempted.stream().noneMatch(value -> value.startsWith("default")), "explicit selection stays on selected mixer");
        attempted.clear();
        try {
            AudioDevices.open(TargetDataLine.class, "higgs.audio.input", "Missing microphone", lines);
            throw new AssertionError("unknown explicit mixer accepted");
        } catch (LineUnavailableException expected) {
            check(expected.getMessage().contains("higgs.audio.input"), "actionable selection failure");
            check(attempted.isEmpty(), "does not record unrelated device when explicit selection is invalid");
        }
        System.out.println("Audio device fallback tests passed");
    }

    private static TargetDataLine fakeLine(AudioFormat format, boolean supported, int[] closed) {
        return (TargetDataLine) Proxy.newProxyInstance(AudioDevicesTest.class.getClassLoader(), new Class<?>[] {TargetDataLine.class}, (proxy, method, args) -> {
            return switch (method.getName()) {
                case "open" -> { if (!supported) throw new LineUnavailableException("Format unavailable"); yield null; }
                case "close" -> { closed[0]++; yield null; }
                case "getFormat" -> format;
                default -> throw new UnsupportedOperationException(method.getName());
            };
        });
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
