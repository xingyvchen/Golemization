package com.example.copper_enchant.client;

public class CopperRenderData {
    private static final ThreadLocal<Boolean> hasEffect = ThreadLocal.withInitial(() -> false);
    private static final ThreadLocal<Float> progress = ThreadLocal.withInitial(() -> 0f);
    private static final ThreadLocal<Float> entityHeight = ThreadLocal.withInitial(() -> 1f);

    public static void set(boolean has, float prog, float height) {
        hasEffect.set(has);
        progress.set(prog);
        entityHeight.set(height);
    }

    public static boolean hasEffect() {
        return hasEffect.get();
    }

    public static float getProgress() {
        return progress.get();
    }

    public static float getEntityHeight() {
        return entityHeight.get();
    }
}
