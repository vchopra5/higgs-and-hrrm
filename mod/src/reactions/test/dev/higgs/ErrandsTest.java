package dev.higgs;

public final class ErrandsTest {
    public static void main(String[] args) {
        check(Errands.shunChance(5) == 0 && Errands.shunChance(0) == 0 && Errands.shunChance(-1) == 0, "liked and neutral villagers always talk");
        check(Errands.shunChance(-2) < Errands.shunChance(-3) && Errands.shunChance(-3) < Errands.shunChance(-4)
            && Errands.shunChance(-4) < Errands.shunChance(-5), "dislike makes walking away likelier");
        check(Errands.shunChance(-5) < 1 && Errands.shunChance(-9) == Errands.shunChance(-5), "even a hostile villager sometimes listens");
        check(Errands.compass(0, -30).equals("north") && Errands.compass(30, 0).equals("east")
            && Errands.compass(0, 30).equals("south") && Errands.compass(-30, 0).equals("west"), "cardinal directions");
        check(Errands.compass(20, -20).equals("north-east") && Errands.compass(-20, 20).equals("south-west"), "diagonals");
        check(Errands.compass(1, 1).equals("right here"), "too close for a direction");
        check(Errands.reward(4, Errands.GIVER_REWARD) == 5 && Errands.reward(-5, Errands.GIVER_REWARD) == -3, "rewards cap at five");
        check(Errands.vague("Cedric says hello and asks if you have a moment to help with a small task.", "Cedric"), "a small task is not a task");
        check(Errands.vague("Emmett sends word: the village needs your help with a small matter.", "Emmett"), "a small matter is not either");
        check(Errands.vague("Hi from Cedric!", "Cedric"), "a greeting alone");
        check(!Errands.vague("Please check the eastern fence line for damage after last night's storm.", "Elin"), "a real request");
        check(!Errands.vague("The wolves took two sheep; keep your gate shut tonight.", "Cedric"), "real news");
        check(!Errands.vague("Could you spare three loaves of bread for the harvest supper?", "Cedric"), "a request with details");
        System.out.println("Errand and shunning checks passed");
    }
    private static void check(boolean value, String label) { if (!value) throw new AssertionError(label); }
}
