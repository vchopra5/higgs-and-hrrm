package dev.higgs;

/** Player-relative range with hysteresis so a neighbour does not flap at the join boundary. */
final class CompanionPresence {
    static boolean withinRange(double distanceSquared, boolean joining) {
        return Double.isFinite(distanceSquared) && distanceSquared >= 0
            && distanceSquared <= (joining ? 64 : 100);
    }
}
