package dev.higgs;

/** A partner-selection press must not also interrupt the new partner's greeting. */
final class SwitchTalkGate {
    private boolean waitingForRelease;
    void switched() { waitingForRelease = true; }
    boolean talking(boolean held) {
        if (!held) waitingForRelease = false;
        return held && !waitingForRelease;
    }
}
