package android.support.v4.content;

import android.content.Context;

/** Minimal compatibility bridge for VolcEngineRTC's audio device layer. */
public final class ContextCompat {
    private ContextCompat() {}

    public static int checkSelfPermission(Context context, String permission) {
        return context.checkSelfPermission(permission);
    }
}
