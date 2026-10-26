package io.github.andreygrigoriev.uc.support;

/** Stand-in for a browser in process tests: sleeps until killed. */
public final class Sleeper {

    private Sleeper() {
    }

    public static void main(String[] args) throws InterruptedException {
        Thread.sleep(Long.MAX_VALUE);
    }
}
