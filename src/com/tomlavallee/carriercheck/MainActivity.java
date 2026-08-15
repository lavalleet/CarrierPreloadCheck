package com.tomlavallee.carriercheck;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.InstallSourceInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.provider.Settings;
import android.widget.Toast;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.ViewGroup.LayoutParams;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

/**
 * Two independent checks:
 *
 * 1. NAMED WATCH -- the five packages that were removed, disabled or stopped,
 *    each with its own expectation. Catches those specific ones coming back.
 *
 * 2. BASELINE DIFF -- a snapshot of every installed package, compared against
 *    what is installed now. Catches anything new regardless of name, which is
 *    what the named watch structurally cannot do. The overnight DT Ignite push
 *    was five packages nobody could have listed in advance; this is the check
 *    that would have caught it.
 *
 * The baseline is stored in SharedPreferences and only changes when the user
 * accepts a new one, so legitimate installs are acknowledged explicitly rather
 * than silently absorbed.
 */
public class MainActivity extends Activity {

    // ---- named watch -------------------------------------------------------

    private static final int EXPECT_UNINSTALLED = 0;
    private static final int EXPECT_DISABLED    = 1;
    private static final int EXPECT_STOPPED     = 2;

    /**
     * ApplicationInfo.FLAG_STOPPED. @hide in the SDK, but this is bit math on
     * the public `flags` field rather than a reflective hidden-API call.
     */
    private static final int FLAG_STOPPED = 0x00200000;

    private static final class Watch {
        final String pkg;
        final int expect;
        final String note;

        Watch(String pkg, int expect, String note) {
            this.pkg = pkg;
            this.expect = expect;
            this.note = note;
        }
    }

    private static final Watch[] WATCHED = {
        new Watch("com.dti.att", EXPECT_UNINSTALLED, null),
        new Watch("com.facebook.system", EXPECT_UNINSTALLED, null),
        new Watch("com.facebook.appmanager", EXPECT_UNINSTALLED, null),
        new Watch("com.samsung.android.game.gos", EXPECT_DISABLED,
                  "reinstalls itself if uninstalled"),
        new Watch("com.samsung.klmsagent", EXPECT_STOPPED,
                  "Knox-protected: cannot be removed or disabled"),
    };

    // ---- baseline ----------------------------------------------------------

    private static final String PREFS = "carriercheck";
    private static final String KEY_BASELINE = "baseline";
    private static final String KEY_CAPTURED = "baseline_captured";

    // ---- result levels -----------------------------------------------------

    private static final int OK    = 0;
    private static final int DRIFT = 1;
    private static final int NOTE  = 2;  // informational; never drives the banner

    private static final int GREEN = 0xFF1E7F52;
    private static final int RED   = 0xFFB3261E;
    private static final int AMBER = 0xFF8A6100;

    private static final class Result {
        final int level;
        final String label;

        Result(int level, String label) {
            this.level = level;
            this.label = label;
        }
    }

    private LinearLayout root;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        ScrollView scroll = new ScrollView(this);
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(24), dp(28), dp(24), dp(28));
        scroll.addView(root, new LayoutParams(LayoutParams.MATCH_PARENT,
                                              LayoutParams.WRAP_CONTENT));
        setContentView(scroll);
    }

    /**
     * Rendering here rather than in onCreate means the list refreshes when the
     * user comes back from the system uninstaller, so a package removed there
     * disappears from the diff without needing a manual re-check.
     */
    @Override
    protected void onResume() {
        super.onResume();
        render();
    }

    private void render() {
        root.removeAllViews();

        // --- named watch
        Result[] results = new Result[WATCHED.length];
        int driftCount = 0;
        for (int i = 0; i < WATCHED.length; i++) {
            results[i] = check(WATCHED[i]);
            if (results[i].level == DRIFT) driftCount++;
        }

        // --- baseline diff
        Set<String> baseline = loadBaseline();
        Set<String> current = currentPackages();

        List<String> added = new ArrayList<>();
        List<String> gone = new ArrayList<>();
        if (baseline != null) {
            for (String p : current) if (!baseline.contains(p)) added.add(p);
            for (String p : baseline) if (!current.contains(p)) gone.add(p);
            Collections.sort(added);
            Collections.sort(gone);
        }

        addTitle("Carrier Preload Check");
        addBanner(driftCount, baseline == null ? -1 : added.size());

        addSection("Watched packages");
        for (int i = 0; i < WATCHED.length; i++) {
            addWatchRow(WATCHED[i], results[i]);
        }

        addSection("Baseline");
        if (baseline == null) {
            addBody("No baseline captured yet. Capture one now to start "
                    + "detecting packages that appear later.");
            addCaptureButton(current, "Capture baseline (" + current.size() + " packages)");
        } else {
            addBaselineSummary(baseline.size(), current.size());

            if (added.isEmpty() && gone.isEmpty()) {
                addStatusLine("✓  no change since baseline", GREEN);
            }

            if (!added.isEmpty()) {
                addStatusLine("✗  " + added.size() + " new since baseline", RED);
                for (String p : added) addPackageDetail(p);
            }

            if (!gone.isEmpty()) {
                addStatusLine("•  " + gone.size() + " no longer present", AMBER);
                for (String p : gone) addSubtle("    " + p);
            }

            if (!added.isEmpty() || !gone.isEmpty()) {
                addCaptureButton(current, "Accept current " + current.size()
                                          + " as new baseline");
            }
        }

        addFooter(driftCount);
        addRecheckButton();
    }

    // ---- named watch checks ------------------------------------------------

    private Result check(Watch w) {
        switch (w.expect) {
            case EXPECT_DISABLED:  return checkDisabled(w.pkg);
            case EXPECT_STOPPED:   return checkStopped(w.pkg);
            default:               return checkUninstalled(w.pkg);
        }
    }

    private Result checkUninstalled(String pkg) {
        PackageManager pm = getPackageManager();

        try {
            pm.getPackageInfo(pkg, 0);
            return new Result(DRIFT, "✗  INSTALLED — it came back");
        } catch (PackageManager.NameNotFoundException expected) {
            // fall through
        }

        try {
            ApplicationInfo ai = pm.getApplicationInfo(
                    pkg, PackageManager.MATCH_UNINSTALLED_PACKAGES);
            boolean installedForUser = (ai.flags & ApplicationInfo.FLAG_INSTALLED) != 0;
            return installedForUser
                    ? new Result(DRIFT, "✗  INSTALLED — it came back")
                    : new Result(OK, "✓  not installed (system stub retained)");
        } catch (PackageManager.NameNotFoundException e) {
            return new Result(OK, "✓  not present at all");
        }
    }

    private Result checkDisabled(String pkg) {
        int state;
        try {
            state = getPackageManager().getApplicationEnabledSetting(pkg);
        } catch (IllegalArgumentException notInstalled) {
            return new Result(OK, "✓  not present at all");
        }

        switch (state) {
            case PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER:
                return new Result(OK, "✓  disabled");
            case PackageManager.COMPONENT_ENABLED_STATE_DISABLED:
                return new Result(OK, "✓  disabled (by system)");
            default:
                return new Result(DRIFT, "✗  ENABLED again");
        }
    }

    private Result checkStopped(String pkg) {
        try {
            ApplicationInfo ai = getPackageManager().getApplicationInfo(pkg, 0);
            boolean stopped = (ai.flags & FLAG_STOPPED) != 0;
            return stopped
                    ? new Result(OK, "✓  stopped")
                    : new Result(NOTE, "•  running — expected after a reboot");
        } catch (PackageManager.NameNotFoundException e) {
            return new Result(OK, "✓  not installed");
        }
    }

    // ---- baseline ----------------------------------------------------------

    private Set<String> currentPackages() {
        Set<String> out = new TreeSet<>();
        for (PackageInfo pi : getPackageManager().getInstalledPackages(0)) {
            out.add(pi.packageName);
        }
        return out;
    }

    private Set<String> loadBaseline() {
        SharedPreferences sp = getSharedPreferences(PREFS, MODE_PRIVATE);
        Set<String> stored = sp.getStringSet(KEY_BASELINE, null);
        return stored == null ? null : new HashSet<>(stored);
    }

    private void saveBaseline(Set<String> pkgs) {
        getSharedPreferences(PREFS, MODE_PRIVATE)
                .edit()
                .putStringSet(KEY_BASELINE, new HashSet<>(pkgs))
                .putLong(KEY_CAPTURED, System.currentTimeMillis())
                .apply();
    }

    private String baselineCapturedAt() {
        long t = getSharedPreferences(PREFS, MODE_PRIVATE).getLong(KEY_CAPTURED, 0L);
        return t == 0L ? "unknown"
                       : new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
                             .format(new Date(t));
    }

    // ---- view construction -------------------------------------------------

    private void addTitle(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setTextColor(themeColor(android.R.attr.textColorPrimary));
        root.addView(tv, lp(0, 0, 0, dp(18)));
    }

    /** newCount < 0 means no baseline has been captured yet. */
    private void addBanner(int driftCount, int newCount) {
        String text;
        int color;

        if (driftCount > 0) {
            text = driftCount + " PACKAGE" + (driftCount == 1 ? "" : "S") + " DRIFTED";
            color = RED;
        } else if (newCount > 0) {
            text = newCount + " NEW PACKAGE" + (newCount == 1 ? "" : "S");
            color = RED;
        } else if (newCount < 0) {
            text = "NO BASELINE";
            color = AMBER;
        } else {
            text = "ALL CLEAR";
            color = GREEN;
        }

        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setTextColor(Color.WHITE);
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(dp(16), dp(20), dp(16), dp(20));

        GradientDrawable bg = new GradientDrawable();
        bg.setColor(color);
        bg.setCornerRadius(dp(14));
        tv.setBackground(bg);

        root.addView(tv, lp(0, 0, 0, dp(22)));
    }

    private void addSection(String text) {
        TextView tv = new TextView(this);
        tv.setText(text.toUpperCase(Locale.US));
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setLetterSpacing(0.08f);
        tv.setTextColor(themeColor(android.R.attr.textColorSecondary));
        root.addView(tv, lp(0, dp(16), 0, dp(8)));
    }

    private void addWatchRow(Watch w, Result r) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(0, dp(9), 0, dp(9));

        TextView name = new TextView(this);
        name.setText(w.pkg);
        name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        name.setTypeface(Typeface.MONOSPACE);
        name.setTextColor(themeColor(android.R.attr.textColorPrimary));
        row.addView(name);

        TextView status = new TextView(this);
        status.setText(r.label);
        status.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        status.setTextColor(r.level == DRIFT ? RED : (r.level == NOTE ? AMBER : GREEN));
        status.setPadding(0, dp(3), 0, 0);
        row.addView(status);

        if (w.note != null) {
            TextView note = new TextView(this);
            note.setText(w.note);
            note.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            note.setTextColor(themeColor(android.R.attr.textColorSecondary));
            note.setPadding(0, dp(2), 0, 0);
            row.addView(note);
        }

        root.addView(row);
    }

    private void addBaselineSummary(int baselineSize, int currentSize) {
        addSubtle("Captured " + baselineCapturedAt()
                  + "  ·  " + baselineSize + " packages then, "
                  + currentSize + " now");
    }

    private void addStatusLine(String text, int color) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setTextColor(color);
        root.addView(tv, lp(0, dp(10), 0, dp(4)));
    }

    /**
     * Install time and installer are the useful forensics: the overnight push
     * showed up as five packages inside three minutes from a non-Play
     * installer, which is what distinguishes it from an install you made.
     */
    private void addPackageDetail(String pkg) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        // Indent the whole block rather than padding the string, so wrapped
        // lines stay aligned instead of running back to the left margin.
        box.setPadding(dp(12), dp(6), 0, dp(6));

        TextView name = new TextView(this);
        name.setText(pkg);
        name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        name.setTypeface(Typeface.MONOSPACE);
        name.setTextColor(themeColor(android.R.attr.textColorPrimary));
        box.addView(name);

        StringBuilder meta = new StringBuilder();
        try {
            PackageInfo pi = getPackageManager().getPackageInfo(pkg, 0);
            meta.append("installed ")
                .append(new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
                            .format(new Date(pi.firstInstallTime)));
        } catch (PackageManager.NameNotFoundException ignored) {
            // Package vanished between enumeration and detail lookup.
        }

        String installer = installerOf(pkg);
        if (installer != null) {
            if (meta.length() > 0) meta.append("  ·  ");
            meta.append("via ").append(installer);
        }

        if (meta.length() > 0) {
            TextView m = new TextView(this);
            m.setText(meta.toString());
            m.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            m.setTextColor(themeColor(android.R.attr.textColorSecondary));
            m.setPadding(0, dp(2), 0, 0);
            box.addView(m);
        }

        box.addView(actionButtonFor(pkg));
        root.addView(box);
    }

    /**
     * One button per package, acting on that package alone -- no batch action
     * and no iteration over the list.
     *
     * A normal app cannot uninstall anything itself, so the two routes differ:
     * an ordinary app is handed to the system uninstaller, while a system app
     * (which the platform will refuse to uninstall from here) goes to its
     * settings page, where Disable is available.
     */
    private Button actionButtonFor(final String pkg) {
        Button b = new Button(this);
        String label = labelOf(pkg);

        if (isSystem(pkg)) {
            b.setText("Open settings for " + label);
            b.setOnClickListener(v -> launch(
                    new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                               Uri.parse("package:" + pkg)),
                    pkg,
                    "System app: uninstall is blocked, use Disable on this screen."));
        } else {
            b.setText("Uninstall " + label);
            b.setOnClickListener(v -> confirmUninstall(pkg, label));
        }

        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        p.setMargins(0, dp(8), 0, dp(4));
        b.setLayoutParams(p);
        return b;
    }

    private void confirmUninstall(final String pkg, String label) {
        new AlertDialog.Builder(this)
                .setTitle("Uninstall " + label + "?")
                .setMessage(pkg + "\n\nAndroid will ask you to confirm. Only this "
                          + "one package is affected.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Continue", (d, w) -> launch(
                        new Intent(Intent.ACTION_DELETE, Uri.parse("package:" + pkg)),
                        pkg, null))
                .show();
    }

    private void launch(Intent intent, String pkg, String hint) {
        try {
            if (hint != null) Toast.makeText(this, hint, Toast.LENGTH_LONG).show();
            startActivity(intent);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "No handler for " + pkg, Toast.LENGTH_LONG).show();
        }
    }

    private boolean isSystem(String pkg) {
        try {
            ApplicationInfo ai = getPackageManager().getApplicationInfo(pkg, 0);
            return (ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    private String labelOf(String pkg) {
        try {
            PackageManager pm = getPackageManager();
            ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
            CharSequence l = pm.getApplicationLabel(ai);
            if (l != null && l.length() > 0) return l.toString();
        } catch (PackageManager.NameNotFoundException ignored) {
            // fall through to the package name
        }
        int dot = pkg.lastIndexOf('.');
        return dot >= 0 && dot < pkg.length() - 1 ? pkg.substring(dot + 1) : pkg;
    }

    private String installerOf(String pkg) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null;
        try {
            InstallSourceInfo si = getPackageManager().getInstallSourceInfo(pkg);
            String name = si.getInstallingPackageName();
            return name == null ? "sideload" : name;
        } catch (PackageManager.NameNotFoundException e) {
            return null;
        }
    }

    private void addBody(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        tv.setTextColor(themeColor(android.R.attr.textColorPrimary));
        root.addView(tv, lp(0, dp(4), 0, dp(10)));
    }

    private void addSubtle(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        tv.setTextColor(themeColor(android.R.attr.textColorSecondary));
        root.addView(tv, lp(0, dp(2), 0, dp(2)));
    }

    /**
     * Accepting a baseline is how a real detection gets silenced, so it asks
     * first and names the count being accepted.
     */
    private void addCaptureButton(final Set<String> current, String label) {
        Button b = new Button(this);
        b.setText(label);
        b.setOnClickListener(v -> new AlertDialog.Builder(MainActivity.this)
                .setTitle("Accept as baseline?")
                .setMessage("The current " + current.size() + " installed packages "
                          + "become the new reference. Anything already listed as "
                          + "new will stop being reported.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Accept", (d, w) -> {
                    saveBaseline(current);
                    render();
                })
                .show());
        root.addView(b, lp(0, dp(12), 0, dp(4)));
    }

    private void addFooter(int driftCount) {
        String stamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
                .format(new Date());

        TextView tv = new TextView(this);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        tv.setTextColor(themeColor(android.R.attr.textColorSecondary));
        tv.setText(driftCount == 0
                ? "Checked " + stamp
                : "Checked " + stamp + "\n\nRe-apply from the Mac:\n"
                  + "adb shell pm uninstall --user 0 <package>\n"
                  + "adb shell pm disable-user --user 0 <package>");
        root.addView(tv, lp(0, dp(20), 0, dp(16)));
    }

    private void addRecheckButton() {
        Button b = new Button(this);
        b.setText("Re-check");
        b.setOnClickListener(v -> render());
        root.addView(b, lp(0, 0, 0, 0));
    }

    // ---- helpers -----------------------------------------------------------

    private LinearLayout.LayoutParams lp(int l, int t, int r, int b) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        p.setMargins(l, t, r, b);
        return p;
    }

    private int themeColor(int attr) {
        TypedValue tv = new TypedValue();
        getTheme().resolveAttribute(attr, tv, true);
        return tv.resourceId != 0 ? getColor(tv.resourceId) : tv.data;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
