package dev.higgs;

public final class AudioLevelsTest {
    public static void main(String[] args) {
        require(AudioLevels.dbfs(new byte[1920], 1920) == -96, "silence floor");
        require(Math.abs(AudioLevels.dbfs(new byte[]{0, 64, 0, -64}, 4) + 6.0206) < 0.001, "signed little endian half amplitude");
        require(Math.abs(AudioLevels.dbfs(new byte[]{0, -128}, 2)) < 0.001, "full scale negative sample");
        require(AudioLevels.dbfs(new byte[]{0}, 1) == -96, "incomplete sample ignored");
        System.out.println("AudioLevels regression checks passed");
    }
    static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
