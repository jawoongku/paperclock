package local.paperclock;

/** Poll only occasionally; failed requests back off without stopping the clock. */
public final class TimeSyncPolicy {
    public static final long INTERVAL = 6L * 60L * 60L * 1000L;

    private TimeSyncPolicy() { }

    public static boolean isDue(long nowElapsed, long lastSuccess, long nextAttempt, boolean force) {
        if (force) return true;
        if (nowElapsed < nextAttempt) return false;
        return lastSuccess < 0 || nowElapsed < lastSuccess || nowElapsed - lastSuccess >= INTERVAL;
    }

    public static long retryDelay(int failures) {
        return Math.min(15L * 60L * 1000L, 60000L << Math.min(4, Math.max(0, failures - 1)));
    }
}
