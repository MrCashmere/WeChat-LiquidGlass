package io.github.liuran001.mmliquidglass;

import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentValues;
import android.content.Context;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.util.TypedValue;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Locale;

/**
 * Diagnostic recorder for the "bottom bar flashes back to its resting shape on
 * a page switch" report.
 *
 * <p>Deliberately observation-only: nothing here changes what the bar does. It
 * exists because the fault shows up on one device/ROM combination we cannot
 * reproduce, and every candidate cause (the host rebuilding its tab row, a
 * dropped-instead-of-animated droplet, the app's own bar slide) reaches the
 * screen through the same few calls — so recording those calls, with ordering
 * and timings, is what tells them apart.
 *
 * <p>Reading it back needs no cable: long-press the droplet and the last few
 * hundred lines appear in a selectable dialog, and the same text is written to
 * a file in Downloads.
 */
final class Trace {

    /** Lines kept in memory. At the burst sizes seen here this covers minutes. */
    private static final int CAP = 500;

    private static final ArrayDeque<String> sLines = new ArrayDeque<>();
    private static final long sT0 = SystemClock.uptimeMillis();
    private static long sPrev;
    private static String sHeader = "(no header yet)";
    private static boolean sHeaderDone;

    private Trace() {
    }

    /** Records one event: elapsed time, gap since the previous one, then the text. */
    static void e(String area, String msg) {
        long now = SystemClock.uptimeMillis();
        long gap = sPrev == 0L ? 0L : now - sPrev;
        sPrev = now;
        String line = String.format(Locale.US, "+%-6d(+%-5d) [%-7s] %s",
                now - sT0, gap, area, msg);
        synchronized (sLines) {
            sLines.addLast(line);
            while (sLines.size() > CAP) {
                sLines.removeFirst();
            }
        }
        // Mirrored to logcat so an adb session sees the same sequence inline.
        LiquidGlassModule.log(android.util.Log.INFO, "TRACE " + line);
    }

    /**
     * One-shot environment header.
     *
     * <p>Built the first time the bar exists, which is the first moment a host
     * Context is available. Kept outside the ring so the oldest lines being
     * evicted can never take it with them.
     */
    static void header(Context ctx) {
        if (sHeaderDone) {
            return;
        }
        sHeaderDone = true;
        StringBuilder b = new StringBuilder();
        b.append("=== LiquidGlass trace ===\n");
        b.append("build        : v0.2.5-diag  pagerRewrite=")
                .append(GlassConfig.pagerRewrite).append('\n');
        b.append("device       : ").append(Build.MANUFACTURER).append(' ')
                .append(Build.MODEL).append(" | ").append(Build.DISPLAY).append('\n');
        b.append("android      : ").append(Build.VERSION.RELEASE)
                .append(" sdk=").append(Build.VERSION.SDK_INT)
                .append(" abi=").append(Build.SUPPORTED_ABIS[0]).append('\n');
        try {
            android.content.pm.PackageInfo pi = ctx.getPackageManager()
                    .getPackageInfo(ctx.getPackageName(), 0);
            b.append("host         : ").append(ctx.getPackageName()).append(' ')
                    .append(pi.versionName).append(" (").append(pi.versionCode).append(")\n");
        } catch (Throwable t) {
            b.append("host         : ").append(ctx.getPackageName()).append(" ?\n");
        }
        android.util.DisplayMetrics dm = ctx.getResources().getDisplayMetrics();
        b.append("screen       : ").append(dm.widthPixels).append('x')
                .append(dm.heightPixels).append(" density=").append(dm.density)
                .append(" dpi=").append(dm.densityDpi).append('\n');
        b.append("night        : ")
                .append((ctx.getResources().getConfiguration().uiMode
                        & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                        == android.content.res.Configuration.UI_MODE_NIGHT_YES)
                .append('\n');
        b.append("========================");
        sHeader = b.toString();
    }

    /** The whole buffer, header first. */
    static String dump() {
        StringBuilder b = new StringBuilder(sHeader).append('\n');
        synchronized (sLines) {
            for (String line : sLines) {
                b.append(line).append('\n');
            }
        }
        return b.toString();
    }

    /**
     * Writes the buffer somewhere the user can reach without root or a cable.
     *
     * <p>Downloads through MediaStore first: an app's own insert there needs no
     * permission on API 29+, and the file lands where any file manager already
     * looks. The app-private external directory is the fallback, and the
     * internal one the last resort.
     */
    static String export(Context ctx) {
        String text = dump();
        byte[] blob = text.getBytes(StandardCharsets.UTF_8);
        String stamp = new java.text.SimpleDateFormat("MMdd-HHmmss", Locale.US)
                .format(new java.util.Date());
        String name = "liquidglass-trace-" + stamp + ".txt";

        if (Build.VERSION.SDK_INT >= 29) {
            Uri entry = null;
            try {
                ContentValues v = new ContentValues();
                v.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
                v.put(MediaStore.MediaColumns.MIME_TYPE, "text/plain");
                v.put(MediaStore.MediaColumns.RELATIVE_PATH,
                        Environment.DIRECTORY_DOWNLOADS + "/LiquidGlass");
                entry = ctx.getContentResolver()
                        .insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
                if (entry != null) {
                    try (java.io.OutputStream os =
                                 ctx.getContentResolver().openOutputStream(entry)) {
                        if (os != null) {
                            os.write(blob);
                            os.flush();
                            return "下载/LiquidGlass/" + name;
                        }
                    }
                }
            } catch (Throwable t) {
                e("export", "media store insert failed: " + t);
                if (entry != null) {
                    try {
                        ctx.getContentResolver().delete(entry, null, null);
                    } catch (Throwable ignored) {
                    }
                }
            }
        }

        File dir = ctx.getExternalFilesDir(null);
        if (dir != null) {
            File f = new File(dir, name);
            try (Writer w = new OutputStreamWriter(
                    new FileOutputStream(f), StandardCharsets.UTF_8)) {
                w.write(text);
                return f.getAbsolutePath();
            } catch (Throwable t) {
                e("export", "external file write failed: " + t);
            }
        }

        try {
            File f = new File(ctx.getFilesDir(), name);
            try (Writer w = new OutputStreamWriter(
                    new FileOutputStream(f), StandardCharsets.UTF_8)) {
                w.write(text);
                return f.getAbsolutePath();
            }
        } catch (Throwable t) {
            e("export", "internal file write failed: " + t);
        }
        return null;
    }

    /** Puts the buffer on screen, selectable, so it can be pasted without a cable. */
    static void show(Context ctx) {
        String where = export(ctx);
        String text = dump();

        // A dialog needs a window token, so it has to be an Activity; the bar's
        // own context is usually a wrapper around one.
        Context ui = unwrap(ctx);
        if (ui == null) {
            LiquidGlassModule.log(android.util.Log.WARN,
                    "trace written but no Activity to show it in: " + where);
            return;
        }

        LinearLayout box = new LinearLayout(ui);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = Math.round(ui.getResources().getDisplayMetrics().density * 12f);
        box.setPadding(pad, pad, pad, pad);

        TextView body = new TextView(ui);
        body.setText(text);
        body.setTextSize(TypedValue.COMPLEX_UNIT_SP, 9f);
        body.setTypeface(Typeface.MONOSPACE);
        body.setTextIsSelectable(true);
        body.setHorizontallyScrolling(false);

        ScrollView scroll = new ScrollView(ui);
        scroll.addView(body);
        box.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        new AlertDialog.Builder(ui)
                .setTitle("LiquidGlass trace (" + sLines.size() + " lines)")
                .setView(box)
                .setPositiveButton("复制全部", (d, w) -> {
                    ClipboardManager cm = (ClipboardManager)
                            ui.getSystemService(Context.CLIPBOARD_SERVICE);
                    if (cm != null) {
                        cm.setPrimaryClip(ClipData.newPlainText("LiquidGlass trace", text));
                        android.widget.Toast.makeText(ui, "已复制",
                                android.widget.Toast.LENGTH_SHORT).show();
                    }
                })
                .setNeutralButton("关闭", null)
                .show();

        if (where != null) {
            android.widget.Toast.makeText(ui, "已写入 " + where,
                    android.widget.Toast.LENGTH_LONG).show();
        }
    }

    /** The nearest Activity behind whatever context the bar was built with. */
    private static Context unwrap(Context ctx) {
        Context c = ctx;
        while (c instanceof android.content.ContextWrapper
                && !(c instanceof android.app.Activity)) {
            Context next = ((android.content.ContextWrapper) c).getBaseContext();
            if (next == null || next == c) {
                break;
            }
            c = next;
        }
        return c instanceof android.app.Activity ? c : null;
    }
}
