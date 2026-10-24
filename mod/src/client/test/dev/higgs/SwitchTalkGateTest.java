package dev.higgs;
public final class SwitchTalkGateTest {
    public static void main(String[] args) {
        var gate = new SwitchTalkGate();
        check(gate.talking(true), "ordinary press records");
        gate.switched();
        check(!gate.talking(true), "switch press cannot interrupt greeting");
        check(!gate.talking(true), "held switch stays suppressed after connection becomes ready");
        check(!gate.talking(false), "release does not send an empty turn");
        check(gate.talking(true), "next press records normally");
        gate.switched();
        gate.talking(false);
        check(!gate.talking(false), "quick tap before Ready leaves greeting alone");
        check(gate.talking(true), "speech works after quick switch");
        System.out.println("Villager switching input checks passed");
    }
    static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
}
