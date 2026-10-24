package dev.higgs;

public final class GossipEncounterTest {
    public static void main(String[] args) {
        var visit = new GossipEncounter(0);
        expect(visit.tick(0, true, true, 64, true), GossipEncounter.Action.APPROACH);
        expect(visit.tick(1000, true, true, 16, true), GossipEncounter.Action.APPROACH);
        expect(visit.tick(2000, true, true, 4, true), GossipEncounter.Action.START_VOICE);
        expect(visit.tick(3000, true, true, 4, true), GossipEncounter.Action.FACE);
        visit.voiceDone();
        expect(visit.tick(3500, true, true, 4, true), GossipEncounter.Action.FACE);
        expect(visit.tick(5000, true, true, 4, true), GossipEncounter.Action.DELIVER);
        expect(visit.tick(5250, true, false, 4, true), GossipEncounter.Action.FACE);
        visit.saved(true, 5500);
        expect(visit.tick(5750, true, false, 4, true), GossipEncounter.Action.REACT);
        expect(visit.tick(7000, true, false, 4, true), GossipEncounter.Action.FINISH);

        var wall = new GossipEncounter(0);
        expect(wall.tick(0, true, true, 1, false), GossipEncounter.Action.APPROACH);
        expect(wall.tick(6001, true, true, 1, false), GossipEncounter.Action.CANCEL);
        wall.voiceDone();
        expect(wall.tick(7000, true, true, 1, true), GossipEncounter.Action.FINISH);

        var interrupted = new GossipEncounter(0);
        expect(interrupted.tick(0, true, true, 1, true), GossipEncounter.Action.START_VOICE);
        expect(interrupted.tick(1000, false, true, 1, true), GossipEncounter.Action.CANCEL);
        var secret = new GossipEncounter(0);
        expect(secret.tick(0, true, false, 4, true), GossipEncounter.Action.CANCEL);
        var separated = new GossipEncounter(0);
        separated.tick(0, true, true, 1, true);
        expect(separated.tick(100, true, true, 16, true), GossipEncounter.Action.CANCEL);

        var hungVoice = new GossipEncounter(0);
        hungVoice.tick(0, true, true, 1, true);
        expect(hungVoice.tick(65_001, true, true, 1, true), GossipEncounter.Action.CANCEL);
        var failedSave = new GossipEncounter(0);
        failedSave.tick(0, true, true, 1, true);
        failedSave.voiceDone();
        expect(failedSave.tick(3000, true, true, 1, true), GossipEncounter.Action.DELIVER);
        failedSave.saved(false, 3100);
        expect(failedSave.tick(3200, true, true, 1, true), GossipEncounter.Action.FINISH);
        if (GossipEncounter.speed(90) <= GossipEncounter.speed(60)) throw new AssertionError("Exciting stories hurry");
        System.out.println("Gossip encounter lifecycle checks passed");
    }
    private static void expect(GossipEncounter.Action got, GossipEncounter.Action expected) {
        if (got != expected) throw new AssertionError("Expected " + expected + ", got " + got);
    }
}
