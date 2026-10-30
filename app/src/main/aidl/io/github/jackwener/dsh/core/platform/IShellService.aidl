package io.github.jackwener.dsh.core.platform;

/** Runs inside Shizuku's shell-uid user service process. */
interface IShellService {
    void destroy() = 16777114;
    /** Runs `sh -c cmd`; returns "<exitCode>\n<combined output>". */
    String exec(String cmd, long timeoutMs) = 1;
    /** Creates the agent's virtual display (flags by Android version, keyboard kept on it); returns displayId or -1. */
    int createDisplay(int width, int height, int dpi) = 2;
    void releaseDisplay(int displayId) = 3;
    /** Latest frame of the virtual display as JPEG, scaled to at most maxWidth px wide (0 = full size); null if none. */
    byte[] captureDisplay(int displayId, int maxWidth) = 4;
    /** 1 when the latest frame is entirely black (protected window), 0 when not, -1 without a frame. */
    int frameBlank(int displayId) = 5;
    /** Tasks on every display, most recent first, one "taskId\tdisplayId\tpackage\ttopActivity\tstandard\tvisible" line each; null when unavailable. */
    String tasks() = 6;
    /** Moves a root task to another display (IActivityTaskManager.moveRootTaskToDisplay, `am display move-stack` as fallback). */
    boolean moveTask(int taskId, int displayId) = 7;
    /** appops PLAY_AUDIO ignore for pkg (the previous mode is kept on disk), or restores it. */
    boolean setAudioMuted(String pkg, boolean muted) = 8;
}
