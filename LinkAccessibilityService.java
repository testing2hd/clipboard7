package com.linkrouter.app;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.ClipData;
import android.widget.TextView;
import android.widget.LinearLayout;
import android.view.ViewGroup;
import android.view.View;
import android.view.MotionEvent;
import android.text.TextUtils;
import android.net.Uri;
import android.graphics.drawable.GradientDrawable;
import android.graphics.Color;
import android.content.Intent;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import android.widget.FrameLayout;
import android.widget.Toast;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.content.res.ColorStateList;
import org.json.JSONArray;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.json.JSONObject;

/**
 * Runs when you tap the "Download video" launcher entry (GrabActivity). In YouTube / TikTok / Instagram / Facebook
 * it presses the app's own Share button, then "Copy link"; in a browser it reads the page address. It then hands the
 * link to the yt-dlp downloader. It subscribes to no events, only looks at the screen after you tap, and nothing
 * leaves the phone except the download itself.
 */
public class LinkAccessibilityService extends AccessibilityService {
    /** ClipWatchService checks this so the copy we trigger does not also open the clipboard popup. */
    static volatile long suppressUntil;
    private static volatile LinkAccessibilityService inst;
    private volatile String nextFolder;

    private static final String[] DEFAULT_APPS = {
        "com.google.android.youtube", "app.revanced.android.youtube", "com.vanced.android.youtube",
        "com.zhiliaoapp.musically", "com.ss.android.ugc.trill", "com.zhiliaoapp.musically.go",
        "com.instagram.android", "com.facebook.katana", "com.facebook.lite"
    };
    private static final String[] BROWSERS = {
        "com.android.chrome", "com.chrome.beta", "com.chrome.dev", "com.chrome.canary", "org.chromium.chrome",
        "org.mozilla.firefox", "org.mozilla.firefox_beta", "org.mozilla.fenix", "com.brave.browser",
        "com.microsoft.emmx", "com.sec.android.app.sbrowser", "com.opera.browser", "com.opera.mini.native",
        "com.kiwibrowser.browser", "com.vivaldi.browser", "com.duckduckgo.mobile.android", "com.yandex.browser",
        "com.UCMobile.intl", "com.mi.globalbrowser", "com.android.browser"
    };
    private static final String[] URL_BAR_IDS = {
        "url_bar", "url_field", "url_view", "location_bar_edit_text", "omnibartextinput",
        "mozac_browser_toolbar_url_view", "mozac_browser_toolbar_edit_url_view", "url_edit_text", "toolbar_url"
    };
    private static final String[] SHARE = {
        "share", "send post", "send reel", "send video", "शेयर", "साझा", "compartilhar", "compartir", "partager",
        "teilen", "condividi", "paylaş", "bagikan", "поделиться", "分享", "共有", "공유", "مشاركة", "chia sẻ"
    };
    private static final String[] COPY_STRONG = {
        "copy link", "copy url", "copy video link", "link copy", "लिङ्क कपी", "लिंक कॉपी", "कपी लिङ्क", "copiar link",
        "copiar enlace", "copier le lien", "link kopieren", "copia link", "bağlantıyı kopyala", "salin link",
        "salin tautan", "копировать ссылку", "复制链接", "リンクをコピー", "링크 복사", "نسخ الرابط", "sao chép liên kết"
    };
    private static final String[] COPY_WEAK = {
        "copy", "कपी", "कॉपी", "copiar", "copier", "kopieren", "copia", "kopyala", "salin", "копировать", "复制", "コピー", "복사", "نسخ"
    };

    private final Handler h = new Handler(Looper.getMainLooper());
    private WindowManager wm;
    private FocusView focusView;
    private boolean busy;
    private int session;
    private int scrolls;
    private boolean viaShare;

    static boolean isEnabled(Context c) {
        try {
            String s = Settings.Secure.getString(c.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            return s != null && s.contains(c.getPackageName() + "/") && s.contains("LinkAccessibilityService");
        } catch (Throwable t) { return false; }
    }

    // ---------- lifecycle ----------

    @Override
    protected void onServiceConnected() {
        inst = this;
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent e) { /* subscribes to no events: it only acts when you tap Download video */ }

    @Override public void onInterrupt() { }

    @Override
    public boolean onUnbind(android.content.Intent i) {
        inst = null;
        session++;
        removeFocus();
        hidePicker();
        return super.onUnbind(i);
    }

    /** Called by GrabActivity (the "Download video" launcher entry). False when the service is not enabled. */
    /** Called by GrabActivity. Shows the folder picker (or starts straight away). False when the service is off. */
    static boolean requestStart() {
        final LinkAccessibilityService s = inst;
        if (s == null) return false;
        s.h.postDelayed(s::begin, 120);   // let the sidebar hand focus back to the video app first
        return true;
    }

    private void begin() {
        if (busy) return;
        showAll = false;
        pickerMode = true;
        showPanel();
    }

    // ---------- the folder picker: a small card, nothing else ----------

    private LinearLayout picker;          // the content
    private CapScroll pickerWin;          // the window: scrolls, never taller than the screen

    /** A ScrollView that never grows taller than 88% of the screen, so it fits portrait and landscape. */
    private static class CapScroll extends ScrollView {
        int maxH;
        CapScroll(Context c) { super(c); }
        @Override
        protected void onMeasure(int w, int hSpec) {
            int h = MeasureSpec.getSize(hSpec);
            int cap = h > 0 ? Math.min(h, maxH) : maxH;
            super.onMeasure(w, MeasureSpec.makeMeasureSpec(cap, MeasureSpec.AT_MOST));
        }
    }
    private WindowManager.LayoutParams pickerLp;
    private boolean pickerMode;      // true: full card (settings, folders, recent); false: progress only
    private boolean showAll;         // "Show more" expanded

    private static final int TEAL = 0xFF0E8F84, TEAL_TXT = 0xFF4FD1C5, OFF = 0xFF2A3145, MUTED = 0xFF8A93A8, RED = 0xFFE5646B;

    private final Runnable autoHide = () -> { if (picker != null && pickerMode) hidePicker(); };

    private GradientDrawable pill(int color, int radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(radiusDp));
        return g;
    }

    private int dp(float v) { return (int) (v * getResources().getDisplayMetrics().density + 0.5f); }

    /** Full-width row. bg 0 = no background, no border (used for plain text chips). */
    private TextView chip(String text, int bg, int heightDp, float sp, int gravity, View.OnClickListener l) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(Color.WHITE);
        t.setTextSize(sp);
        t.setGravity(gravity | Gravity.CENTER_VERTICAL);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        t.setPadding(dp(16), 0, dp(16), 0);
        if (bg != 0) t.setBackground(pill(bg, heightDp / 2));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(heightDp));
        lp.topMargin = dp(6);
        t.setLayoutParams(lp);
        t.setOnClickListener(l);
        return t;
    }

    /** Equal-width chip for a horizontal row. */
    private TextView hchip(String text, boolean on, View.OnClickListener l) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(on ? Color.WHITE : 0xFFC3CAD8);
        t.setTextSize(13);
        t.setTypeface(on ? android.graphics.Typeface.DEFAULT_BOLD : android.graphics.Typeface.DEFAULT);
        t.setGravity(Gravity.CENTER);
        t.setSingleLine(true);
        t.setBackground(pill(on ? TEAL : OFF, 17));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(34), 1f);
        lp.rightMargin = dp(6);
        t.setLayoutParams(lp);
        t.setOnClickListener(l);
        return t;
    }

    private LinearLayout hrow() {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(6);
        r.setLayoutParams(lp);
        return r;
    }

    private TextView lbl(String s, float sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        return t;
    }

    private TextView section(String s) {
        TextView t = lbl(s, 11, MUTED, false);
        t.setPadding(dp(8), dp(12), 0, 0);
        return t;
    }

    private String getDl(String k, String def) {
        try { return Config.dl(this).optString(k, def); } catch (Throwable t) { return def; }
    }

    private void setDl(String k, String v) {
        try {
            JSONObject cfg = Config.load(this);
            cfg.getJSONObject("dl").put(k, v);
            Config.save(this, cfg);
        } catch (Throwable ignored) { }
    }

    /** Shows the full card: quick settings, folders, recent downloads (and live progress when one is running). */
    private void showPanel() {
        hidePicker();
        final LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(10), dp(6), dp(10), dp(10));
        final CapScroll win = new CapScroll(this);
        win.maxH = (int) (getResources().getDisplayMetrics().heightPixels * 0.88f);
        win.setBackground(pill(0xF01B2030, 22));
        win.setVerticalScrollBarEnabled(false);
        win.setOverScrollMode(View.OVER_SCROLL_NEVER);
        win.addView(card, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        win.setOnTouchListener((v, ev) -> {
            if (ev.getAction() == MotionEvent.ACTION_OUTSIDE && pickerMode) hidePicker();
            return false;
        });
        WindowManager.LayoutParams p = new WindowManager.LayoutParams(dp(290), ViewGroup.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
                PixelFormat.TRANSLUCENT);
        p.gravity = Gravity.CENTER_VERTICAL | Gravity.END;
        p.x = dp(14);
        p.y = 0;
        try {
            wm.addView(win, p);
            pickerWin = win;
            picker = card;
            pickerLp = p;
        } catch (Throwable t) {
            picker = null;
            pickerWin = null;
            if (pickerMode) { nextFolder = null; grab(); }   // could not draw the card: just use the default folder
            return;
        }
        render();
    }

    private void render() {
        if (picker == null) return;
        final LinearLayout card = picker;
        final int keepY = pickerWin == null ? 0 : pickerWin.getScrollY();
        card.removeAllViews();

        // ----- quick settings: video / audio + quality -----
        final boolean audio = "audio".equals(getDl("mode", "video"));
        LinearLayout seg = hrow();
        seg.addView(hchip("Video", !audio, v -> { setDl("mode", "video"); render(); }));
        seg.addView(hchip("Audio", audio, v -> { setDl("mode", "audio"); render(); }));
        card.addView(seg);
        final String[] vals = audio ? new String[]{"m4a", "mp3", "opus"} : new String[]{"best", "1080", "720", "480"};
        final String[] labs = audio ? new String[]{"M4A", "MP3", "Opus"} : new String[]{"Best", "1080p", "720p", "480p"};
        final String key = audio ? "audioFormat" : "quality";
        String cur = getDl(key, vals[0]);
        LinearLayout opts = hrow();
        for (int i = 0; i < vals.length; i++) {
            final String val = vals[i];
            opts.addView(hchip(labs[i], val.equals(cur), v -> { setDl(key, val); render(); }));
        }
        card.addView(opts);

        // ----- folders: small chips in a short scrolling list -----
        List<String> folders = new ArrayList<>();
        boolean ask = true;
        try {
            ask = Config.dl(this).optBoolean("askFolder", true);
            for (String f : Config.dl(this).optString("categories", "").split("\\n")) if (!f.trim().isEmpty()) folders.add(f.trim());
        } catch (Throwable ignored) { }
        card.addView(section(audio ? "Save to (Music)" : "Save to (Movies)"));
        if (!ask || folders.isEmpty()) {
            card.addView(chip("Download", TEAL, 42, 15, Gravity.CENTER, v -> { hidePicker(); nextFolder = null; grab(); }));
        } else {
            LinearLayout fl = new LinearLayout(this);
            fl.setOrientation(LinearLayout.VERTICAL);
            for (final String f : folders) {
                fl.addView(chip(f, TEAL, 42, 15, Gravity.CENTER, v -> { hidePicker(); nextFolder = f; grab(); }));
            }
            card.addView(fl);
        }

        // ----- recent downloads: 2 shown, "Show more" (no border) for the rest, scrolls -----
        List<JSONObject> done = new ArrayList<>();
        try {
            JSONArray hist = Downloader.history(this);
            for (int i = 0; i < hist.length() && done.size() < 12; i++) {
                JSONObject e = hist.getJSONObject(i);
                if ("done".equals(e.optString("status")) && !e.optString("uri").isEmpty()) done.add(e);
            }
        } catch (Throwable ignored) { }
        if (!done.isEmpty()) {
            card.addView(section("Recent"));
            int limit = showAll ? done.size() : Math.min(3, done.size());
            LinearLayout list = new LinearLayout(this);
            list.setOrientation(LinearLayout.VERTICAL);
            for (int i = 0; i < limit; i++) {
                final JSONObject e = done.get(i);
                list.addView(chip("\u2713 " + e.optString("title"), 0, 34, 13, Gravity.START, v -> { hidePicker(); openFile(e.optString("uri")); }));
            }
            card.addView(list);
            if (done.size() > 3) {
                TextView more = chip(showAll ? "Show less" : "Show more", 0, 32, 13, Gravity.START, v -> { showAll = !showAll; render(); });
                more.setTextColor(TEAL_TXT);
                card.addView(more);
            }
        }
        try { if (pickerLp != null && pickerWin != null) wm.updateViewLayout(pickerWin, pickerLp); } catch (Throwable ignored) { }
        if (keepY > 0 && pickerWin != null) { final CapScroll w = pickerWin; w.post(() -> w.scrollTo(0, keepY)); }
        h.removeCallbacks(autoHide);
        h.postDelayed(autoHide, 15000);
    }

    private void hidePicker() {
        h.removeCallbacks(autoHide);
        if (pickerWin == null) { picker = null; return; }
        try { wm.removeView(pickerWin); } catch (Throwable ignored) { }
        pickerWin = null;
        picker = null;
    }

    /** After Copy link the app's share sheet can stay open: press Back (at most twice) while "Copy link" is still on screen. */
    private void closeShareSheet(final int n) {
        if (n >= 2 || busy) return;
        List<Cand> cands = new ArrayList<>();
        int centerY = getResources().getDisplayMetrics().heightPixels / 2;
        try {
            List<AccessibilityWindowInfo> ws = getWindows();
            if (ws != null) {
                for (AccessibilityWindowInfo w : ws) {
                    AccessibilityNodeInfo r = w.getRoot();
                    if (r == null || ours(r)) continue;
                    collect(r, cands, new int[]{2500}, centerY);
                }
            }
        } catch (Throwable ignored) { }
        if (pick(cands, words(COPY_STRONG, "bubbleCopy")) == null) return;
        performGlobalAction(GLOBAL_ACTION_BACK);
        h.postDelayed(() -> closeShareSheet(n + 1), 400);
    }

    private void openFile(String uri) {
        try {
            Uri u = Uri.parse(uri);
            startActivity(new Intent(Intent.ACTION_VIEW).setDataAndType(u, getContentResolver().getType(u))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION));
        } catch (Throwable t) { toast("Couldn't open that file."); }
    }

    /** The video app's window, even when something else (sidebar, our invisible activity) briefly has focus. */
    private AccessibilityNodeInfo appRoot() {
        Set<String> t = targets();
        AccessibilityNodeInfo best = null;
        try {
            List<AccessibilityWindowInfo> ws = getWindows();
            if (ws != null) {
                for (AccessibilityWindowInfo w : ws) {
                    if (w.getType() != AccessibilityWindowInfo.TYPE_APPLICATION) continue;
                    AccessibilityNodeInfo r = w.getRoot();
                    CharSequence p = r == null ? null : r.getPackageName();
                    if (p != null && t.contains(p.toString()) && (best == null || w.isActive())) best = r;
                }
            }
        } catch (Throwable ignored) { }
        return best != null ? best : getRootInActiveWindow();
    }

    // ---------- which app is in front ----------

    private Set<String> targets() {
        Set<String> s = new HashSet<>(Arrays.asList(DEFAULT_APPS));
        for (String x : csv("bubbleApps")) s.add(x);
        s.addAll(browsers());
        return s;
    }

    private Set<String> browsers() {
        Set<String> s = new HashSet<>(Arrays.asList(BROWSERS));
        for (String x : csv("bubbleBrowsers")) s.add(x);
        return s;
    }

    private List<String> csv(String key) {
        List<String> out = new ArrayList<>();
        try {
            for (String x : Config.dl(this).optString(key, "").split("[,\\n]")) {
                x = x.trim().toLowerCase(Locale.ROOT);
                if (!x.isEmpty()) out.add(x);
            }
        } catch (Throwable ignored) { }
        return out;
    }

    private int overlayType() {
        return Settings.canDrawOverlays(this) ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY;
    }

    private boolean ours(AccessibilityNodeInfo r) {
        CharSequence p = r.getPackageName();
        return p != null && getPackageName().contentEquals(p);
    }

    private void toast(String m) { Toast.makeText(this, m, Toast.LENGTH_SHORT).show(); }

    private void done() {
        busy = false;
        session++;
    }

    // ---------- the automation ----------

    private void grab() {
        if (busy) return;
        busy = true;
        scrolls = 0;
        viaShare = false;
        final int sid = ++session;
        final long t0 = System.currentTimeMillis();
        suppressUntil = SystemClock.elapsedRealtime() + 9000;
        toast("Getting the link\u2026");
        AccessibilityNodeInfo r = appRoot();
        CharSequence pkg = r == null ? null : r.getPackageName();
        if (pkg != null && browsers().contains(pkg.toString())) browserRead(sid, t0, 0, false);
        else findShare(sid, t0, 0);
    }

    private void fail(int sid, String msg) {
        if (sid != session) return;
        toast(msg);
        done();
    }

    private static class Cand {
        AccessibilityNodeInfo node;
        String label;
        int dist;
    }

    private static String label(AccessibilityNodeInfo n) {
        CharSequence t = n.getText(), d = n.getContentDescription();
        String a = t == null ? "" : t.toString();
        String b = d == null ? "" : d.toString();
        return (a.equals(b) ? a : (a + " " + b)).trim().toLowerCase(Locale.ROOT);
    }

    private void collect(AccessibilityNodeInfo n, List<Cand> out, int[] budget, int centerY) {
        if (n == null || budget[0]-- <= 0) return;
        if (n.isVisibleToUser()) {
            String l = label(n);
            if (!l.isEmpty() && l.length() <= 48) {
                Cand c = new Cand();
                c.node = n;
                c.label = l;
                Rect r = new Rect();
                n.getBoundsInScreen(r);
                c.dist = Math.abs(r.centerY() - centerY);
                out.add(c);
            }
        }
        for (int i = 0; i < n.getChildCount(); i++) collect(n.getChild(i), out, budget, centerY);
    }

    private static int score(String l, List<String> words) {
        int best = 0;
        for (int i = 0; i < words.size(); i++) {
            String w = words.get(i);
            int s = l.equals(w) ? 3000 : l.startsWith(w) ? 2000 : l.contains(w) ? 1000 : 0;
            if (s > 0) best = Math.max(best, s - i);
        }
        return best;
    }

    private List<String> words(String[] base, String customKey) {
        List<String> w = new ArrayList<>(csv(customKey));
        w.addAll(Arrays.asList(base));
        return w;
    }

    /** Best match: highest score, and among equals the one nearest the middle of the screen. */
    private Cand pick(List<Cand> cands, List<String> words) {
        Cand best = null;
        int bestScore = 0;
        for (Cand c : cands) {
            int s = score(c.label, words);
            if (s == 0) continue;
            if (best == null || s > bestScore || (s == bestScore && c.dist < best.dist)) { best = c; bestScore = s; }
        }
        return best;
    }

    private void findShare(final int sid, final long t0, final int attempt) {
        if (sid != session) return;
        List<Cand> cands = new ArrayList<>();
        int centerY = getResources().getDisplayMetrics().heightPixels / 2;
        try { collect(appRoot(), cands, new int[]{2500}, centerY); } catch (Throwable ignored) { }
        Cand best = pick(cands, words(SHARE, "bubbleShare"));
        if (best == null) {
            if (attempt < 6) { h.postDelayed(() -> findShare(sid, t0, attempt + 1), 100); return; }
            fail(sid, "Couldn't find a Share button here. Open a video first.");
            return;
        }
        if (!click(best.node)) { fail(sid, "Couldn't press Share."); return; }
        h.postDelayed(() -> pollCopy(sid, t0, 0), 100);
    }

    private void pollCopy(final int sid, final long t0, final int attempt) {
        if (sid != session) return;
        List<Cand> cands = new ArrayList<>();
        int centerY = getResources().getDisplayMetrics().heightPixels / 2;
        try {
            List<AccessibilityWindowInfo> ws = getWindows();
            if (ws != null) {
                for (AccessibilityWindowInfo w : ws) {
                    AccessibilityNodeInfo r = w.getRoot();
                    if (r == null || ours(r)) continue;
                    collect(r, cands, new int[]{2500}, centerY);
                }
            }
        } catch (Throwable ignored) { }
        Cand best = pick(cands, words(COPY_STRONG, "bubbleCopy"));
        if (best == null && attempt >= 25) best = pick(cands, Arrays.asList(COPY_WEAK));
        if (best != null) {
            if (!click(best.node)) { fail(sid, "Couldn't press Copy link."); return; }
            viaShare = true;
            h.postDelayed(() -> readClip(sid, t0, 0), 150);
            return;
        }
        if (attempt >= 15 && attempt % 10 == 5 && scrolls < 3) { scrolls++; scrollRow(cands); }
        if (attempt >= 70) { fail(sid, "Couldn't find \"Copy link\" in this app's share menu."); return; }
        h.postDelayed(() -> pollCopy(sid, t0, attempt + 1), 60);
    }

    /** The share row can hide "Copy link" off to the side. Only short rows in the lower screen are scrolled, never the video pager. */
    private void scrollRow(List<Cand> unused) {
        try {
            DisplayMetrics dm = getResources().getDisplayMetrics();
            List<AccessibilityWindowInfo> ws = getWindows();
            if (ws == null) return;
            for (AccessibilityWindowInfo w : ws) {
                AccessibilityNodeInfo r = w.getRoot();
                if (r == null || ours(r)) continue;
                if (scrollIn(r, dm, new int[]{2000})) return;
            }
        } catch (Throwable ignored) { }
    }

    private boolean scrollIn(AccessibilityNodeInfo n, DisplayMetrics dm, int[] budget) {
        if (n == null || budget[0]-- <= 0) return false;
        if (n.isScrollable() && n.isVisibleToUser()) {
            Rect r = new Rect();
            n.getBoundsInScreen(r);
            if (r.height() < dm.heightPixels * 0.4 && r.top > dm.heightPixels * 0.35
                    && n.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) return true;
        }
        for (int i = 0; i < n.getChildCount(); i++) if (scrollIn(n.getChild(i), dm, budget)) return true;
        return false;
    }

    private boolean click(AccessibilityNodeInfo n) {
        AccessibilityNodeInfo c = n;
        for (int i = 0; i < 6 && c != null; i++) {
            if (c.isClickable() && c.isEnabled()) {
                if (c.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true;
                break;
            }
            c = c.getParent();
        }
        Rect r = new Rect();
        n.getBoundsInScreen(r);
        Path p = new Path();
        p.moveTo(r.centerX(), r.centerY());
        return dispatchGesture(new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(p, 0, 60)).build(), null, null);
    }

    // ---------- browsers: read the page address instead of using Share ----------

    private static String rawText(AccessibilityNodeInfo n) {
        CharSequence t = n.getText();
        return t == null ? "" : t.toString().trim();
    }

    /** "https://x.com/a", or "x.com/a" (scheme added), or null when the text is not an address. */
    private static String toUrl(String t) {
        if (t.isEmpty() || t.matches(".*\\s.*")) return null;
        if (t.matches("(?i)^https?://\\S+$")) return t;
        if (t.matches("(?i)^[a-z0-9-]+(\\.[a-z0-9-]+)+(:\\d+)?([/?#].*)?$")) return "https://" + t;
        return null;
    }

    private static boolean hasPath(String url) { return url.replaceFirst("(?i)^https?://[^/?#]+/?", "").length() > 0; }

    private void findUrlBars(AccessibilityNodeInfo n, List<AccessibilityNodeInfo> out, int[] budget) {
        if (n == null || budget[0]-- <= 0) return;
        String id = n.getViewIdResourceName();
        if (id != null) {
            String low = id.toLowerCase(Locale.ROOT);
            for (String k : URL_BAR_IDS) if (low.endsWith("/" + k) || low.endsWith(":id/" + k)) { out.add(n); break; }
        }
        for (int i = 0; i < n.getChildCount(); i++) findUrlBars(n.getChild(i), out, budget);
    }

    /**
     * Browsers show only the site name until you tap the address bar. So: read the bar; if there is no path,
     * tap it once to reveal the full address, read that, then close the keyboard again.
     */
    private void browserRead(final int sid, final long t0, final int attempt, final boolean expanded) {
        if (sid != session) return;
        List<AccessibilityNodeInfo> bars = new ArrayList<>();
        try { findUrlBars(appRoot(), bars, new int[]{2500}); } catch (Throwable ignored) { }
        String best = null;
        AccessibilityNodeInfo bar = null;
        for (AccessibilityNodeInfo b : bars) {
            String u = toUrl(rawText(b));
            if (u != null && (best == null || u.length() > best.length())) { best = u; bar = b; }
        }
        if (best != null && (hasPath(best) || (expanded && attempt >= 8))) {
            launch(sid, best);
            if (expanded) h.postDelayed(() -> restoreBar(1), 150);
            return;
        }
        if (best != null && !expanded) {
            if (!click(bar)) { fail(sid, "Couldn't open the address bar."); return; }
            h.postDelayed(() -> browserRead(sid, t0, 0, true), 150);
            return;
        }
        if (best == null && attempt >= 15) { fail(sid, "Couldn't read this page's address."); return; }
        h.postDelayed(() -> browserRead(sid, t0, attempt + 1, expanded), 60);
    }

    private boolean imeVisible() {
        try {
            List<AccessibilityWindowInfo> ws = getWindows();
            if (ws != null) for (AccessibilityWindowInfo w : ws) if (w.getType() == AccessibilityWindowInfo.TYPE_INPUT_METHOD) return true;
        } catch (Throwable ignored) { }
        return false;
    }

    /** Press Back only while the keyboard or the address bar is still open (at most twice), so the page never goes back. */
    private void restoreBar(final int n) {
        List<AccessibilityNodeInfo> bars = new ArrayList<>();
        try { findUrlBars(appRoot(), bars, new int[]{2500}); } catch (Throwable ignored) { }
        boolean focused = false;
        for (AccessibilityNodeInfo b : bars) if (b.isFocused()) focused = true;
        if ((focused || imeVisible()) && n <= 2) {
            performGlobalAction(GLOBAL_ACTION_BACK);
            h.postDelayed(() -> restoreBar(n + 1), 150);
        }
    }

    // ---------- reading the copied link ----------

    private String freshText(long t0) {
        try {
            ClipData d = ((ClipboardManager) getSystemService(CLIPBOARD_SERVICE)).getPrimaryClip();
            if (d == null || d.getItemCount() == 0) return null;
            if (d.getDescription().getTimestamp() < t0 - 1500) return "";   // an older clip: the copy has not happened (yet)
            CharSequence cs = d.getItemAt(0).coerceToText(this);
            return cs == null ? null : cs.toString();
        } catch (Throwable t) { return null; }
    }

    /** null = clipboard not readable from here, "" = only an old clip, otherwise the fresh text. */
    private void readClip(final int sid, final long t0, final int attempt) {
        if (sid != session) return;
        String t = freshText(t0);
        if (t == null) { focusRead(sid, t0); return; }              // background read is blocked: take focus briefly
        if (t.isEmpty()) {
            if (attempt < 8) { h.postDelayed(() -> readClip(sid, t0, attempt + 1), 120); return; }
            fail(sid, "The link wasn't copied. Try again.");
            return;
        }
        finish(sid, t);
    }

    private static class FocusView extends FrameLayout {
        Runnable onFocus;
        boolean fired;
        FocusView(Context c) { super(c); }

        @Override
        public void onWindowFocusChanged(boolean has) {
            super.onWindowFocusChanged(has);
            if (has && !fired && onFocus != null) { fired = true; onFocus.run(); }
        }
    }

    /** A 1px focusable window: while it has focus Android lets us read the clipboard. */
    private void focusRead(final int sid, final long t0) {
        removeFocus();
        final FocusView v = new FocusView(this);
        v.onFocus = () -> h.postDelayed(() -> readFocused(sid, t0, v, 0), 60);
        try {
            WindowManager.LayoutParams p = new WindowManager.LayoutParams(1, 1, overlayType(),
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                    PixelFormat.TRANSLUCENT);
            p.gravity = Gravity.TOP | Gravity.START;
            wm.addView(v, p);
            focusView = v;
        } catch (Throwable t) { fail(sid, "Couldn't read the copied link."); return; }
        h.postDelayed(() -> {
            if (focusView == v && !v.fired) { removeFocus(); fail(sid, "Couldn't read the copied link."); }
        }, 2500);
    }

    private void readFocused(int sid, long t0, FocusView v, int attempt) {
        if (sid != session || focusView != v) return;
        String t = freshText(t0);
        if (t == null || t.isEmpty()) {
            if (attempt < 8) { h.postDelayed(() -> readFocused(sid, t0, v, attempt + 1), 120); return; }
            removeFocus();
            fail(sid, "The link wasn't copied. Try again.");
            return;
        }
        finish(sid, t);   // starts the download while the overlay is still up, then removes it
    }

    private void removeFocus() {
        if (focusView == null) return;
        try { wm.removeView(focusView); } catch (Throwable ignored) { }
        focusView = null;
    }

    private void finish(int sid, String text) {
        if (sid != session) return;
        String url = Config.extractUrl(text);
        if (url == null || !Config.matches(url)) {
            removeFocus();
            fail(sid, "That isn't a supported video link.");
            return;
        }
        launch(sid, url);
    }

    /** Starts the download. Browser pages skip the site check: yt-dlp supports far more sites than the four apps. */
    private void launch(int sid, String url) {
        if (sid != session) return;
        suppressUntil = SystemClock.elapsedRealtime() + 4000;
        try {
            String folder = nextFolder;
            nextFolder = null;
            DownloadService.start(this, url, null, folder);
            toast(folder == null || folder.isEmpty() ? "Downloading\u2026" : "Downloading to " + folder + "\u2026");
            removeFocus();
            done();
            if (viaShare) { viaShare = false; h.postDelayed(() -> closeShareSheet(0), 350); }
        } catch (Throwable t) {
            removeFocus();
            fail(sid, "Android blocked the download from the background. Open Link Router and allow \"Display over other apps\".");
        }
    }
}
