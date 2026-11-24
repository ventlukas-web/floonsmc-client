package dev.floons.client;

/** Stoppuhr (/stopwatch) und Timer (/timer) wie im Toasty Client. */
public final class FloonsTimers {
    private FloonsTimers() {}

    // ---------- Stoppuhr (/stopwatch) ----------
    private static long swStart = 0, swAcc = 0;
    private static boolean swRun = false;
    private static final java.util.List<Long> LAPS = new java.util.ArrayList<>();

    public static long swElapsed() { return swAcc + (swRun ? System.currentTimeMillis() - swStart : 0); }
    public static boolean swRunning() { return swRun; }
    public static boolean swActive() { return swRun || swAcc > 0; }

    public static String swFmt(long ms) {
        long s = ms / 1000;
        return String.format(java.util.Locale.ROOT, "%d:%02d.%d", s / 60, s % 60, (ms % 1000) / 100);
    }

    /** start / stop / lap / reset */
    public static String stopwatch(String cmd) {
        switch (cmd) {
            case "start" -> { if (!swRun) { swStart = System.currentTimeMillis(); swRun = true; } return "Stoppuhr läuft."; }
            case "stop" -> { if (swRun) { swAcc = swElapsed(); swRun = false; } return "Stoppuhr gestoppt: " + swFmt(swAcc); }
            case "lap" -> { long t = swElapsed(); LAPS.add(t); return "Runde " + LAPS.size() + ": " + swFmt(t); }
            default -> { swRun = false; swAcc = 0; LAPS.clear(); return "Stoppuhr zurückgesetzt."; }
        }
    }

    // ---------- Timer (/timer) ----------
    private static final java.util.List<Object[]> TIMERS = new java.util.ArrayList<>();

    public static void addTimer(int sec, String name) {
        TIMERS.add(new Object[]{System.currentTimeMillis() + sec * 1000L, name});
    }

    /** Verbleibende Zeit je Timer als Text, z. B. "Auszahlung 0:42". */
    public static java.util.List<String> timerLines() {
        java.util.List<String> l = new java.util.ArrayList<>();
        long now = System.currentTimeMillis();
        for (Object[] t : TIMERS) {
            long s = Math.max(0, ((Long) t[0] - now + 999) / 1000);
            l.add("Timer " + t[1] + "  " + (s / 60) + ":" + String.format(java.util.Locale.ROOT, "%02d", s % 60));
        }
        return l;
    }

    public static int cancelTimers() { int n = TIMERS.size(); TIMERS.clear(); return n; }

    private static void timerTick() {
        if (TIMERS.isEmpty()) return;
        long now = System.currentTimeMillis();
        for (java.util.Iterator<Object[]> it = TIMERS.iterator(); it.hasNext(); ) {
            Object[] t = it.next();
            if (now >= (Long) t[0]) {
                it.remove();
                FloonsNotify.push("Timer fertig", (String) t[1], 0xFF4ADE80);
                FloonsGamble.chat("Timer fertig: " + t[1]);
            }
        }
    }

    public static void tick() { timerTick(); }
}
