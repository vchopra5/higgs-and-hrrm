package dev.higgs;

/** Server-authoritative recipient and turn binding for a merchant session. */
final class MerchantTurn {
    private final int merchant;
    private int recipient, turn;
    MerchantTurn(int merchant) { this.merchant = merchant; this.recipient = merchant; }
    boolean address(int recipient, int turn) {
        if (turn < this.turn) return false;
        this.recipient = recipient;
        this.turn = turn;
        return true;
    }
    boolean permits(int merchant, int turn) {
        return merchant == this.merchant && recipient == merchant && turn == this.turn;
    }
    boolean addressed(int recipient, int turn) { return this.recipient == recipient && this.turn == turn; }
}
