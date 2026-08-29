package io.github.rsegordon.litematic_render;

/** Shared render state for the two background passes used to recover PNG alpha. */
public final class BackgroundPass {
    private static volatile boolean white;

    private BackgroundPass() {}

    public static boolean isWhite() { return white; }
    public static void setWhite(boolean value) { white = value; }
}
