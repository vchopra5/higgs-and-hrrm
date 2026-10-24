package dev.higgs;

public final class TextTermsTest {
    public static void main(String[] args) {
        String message = "Tell Bram the wolves are back near the eastern fields.";
        check(TextTerms.conveys("Iris says the wolves are back by the east fields", message, "Iris"), "a paraphrase carries it");
        check(TextTerms.conveys("Wolf back, eastern field", message, "Iris"), "plurals and terse speech");
        check(TextTerms.conveys("Iris sent me, something about wolves", message, "Iris"), "the sender plus a key word");
        check(!TextTerms.conveys("Iris sent me to say hello", message, "Iris"), "naming the sender alone is not the message");
        check(!TextTerms.conveys("How much is bread today?", message, "Iris"), "an unrelated sentence is not");
        check(!TextTerms.conveys("", message, "Iris"), "silence is not");
        String named = "Cedric says hello and asks if you have a moment to help with a small task.";
        check(TextTerms.conveys("Cedric says hi, and he wanted you for a small task.", named, "Cedric"), "a loose paraphrase naming the sender");
        check(!TextTerms.conveys("Go talk to Cedric and finish the quest for Nathan.", named, "Cedric"), "the sender's name is not a content word");
        check(TextTerms.words("The wolves' den, Bram's field!").equals(java.util.List.of("wolves", "den", "bram", "field")), "same analysis as retrieval");
        System.out.println("Message relay checks passed");
    }
    private static void check(boolean value, String label) { if (!value) throw new AssertionError(label); }
}
