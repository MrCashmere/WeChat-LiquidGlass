package io.github.liuran001.mmliquidglass;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * The one tunable the bar exposes, read from the host app's own SharedPreferences.
 *
 * <p>Nothing in the module writes this file — there is no settings UI — but it
 * lets the float height be changed without a rebuild, which is the only value
 * that is really a matter of taste. Each host keeps its own copy, since the
 * file is read through that app's context and lands in that app's data dir.
 */
final class GlassConfig {

    /** Named before QQ was a target; kept so existing WeChat setups still read. */
    private static final String PREFS = "wx_liquid_glass_cfg";

    /** Distance between the bottom of the glass pill and the screen edge, dp. */
    static volatile int barOffsetDp = 12;

    /**
     * How far a tab tap may be turned from the app's instant cut into a slide.
     *
     * <pre>
     * 0  never — the app's own setCurrentItem is left exactly as written
     * 1  only between tabs the user sees as neighbours (default)
     * 2  also over the pages in between, held back from the host
     * </pre>
     *
     * <p>1 is the default because the neighbours case is the shape the hosts'
     * own page-change handling was written for, and it is the case that fixes
     * WeChat's vanishing top-bar text. 2 makes a long jump slide too, but it
     * has to hide the pages it passes from the host, and that is the one thing
     * here capable of stranding the host's own bar mid-animation. Exposed so
     * the three can be told apart on a device without a rebuild.
     */
    static volatile int pagerRewrite = 1;

    /**
     * Whether the module may reshape the window for the floating bar.
     *
     * <p>On: the window is grown under the gesture bar, the system navigation
     * backdrop is hidden, and the app is handed navigation insets of zero — so
     * the pill can float over content that reaches the screen edge.
     *
     * <p>Off: the window is left exactly as the app built it, and the pill sits
     * above the gesture area the way the stock bar did. Every one of those three
     * is a window-level change that lives no longer than the app process, but a
     * ROM that adapts its own gesture bar badly under an edge-to-edge window
     * reads better with this off. Takes effect on the next install, so restart
     * the host app after changing it.
     */
    static volatile int navExtension = 1;

    private GlassConfig() {
    }

    static void load(Context ctx) {
        try {
            SharedPreferences p = ctx.getSharedPreferences(PREFS, 0);
            barOffsetDp = p.getInt("barOffsetDp", barOffsetDp);
            int mode = p.getInt("pagerRewrite", pagerRewrite);
            pagerRewrite = mode < 0 ? 0 : Math.min(mode, 2);
            navExtension = p.getInt("navExtension", navExtension) > 0 ? 1 : 0;
        } catch (Throwable t) {
            LiquidGlassModule.logErr("config load failed", t);
        }
    }
}
