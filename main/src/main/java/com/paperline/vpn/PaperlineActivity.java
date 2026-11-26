package com.paperline.vpn;

import android.Manifest;
import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.InsetDrawable;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.net.VpnService;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.util.AtomicFile;
import android.util.LruCache;
import android.util.SparseArray;
import android.view.ContextThemeWrapper;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.Window;
import android.view.WindowInsets;
import android.view.animation.LinearInterpolator;
import android.view.animation.OvershootInterpolator;
import android.view.animation.PathInterpolator;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import de.blinkt.openvpn.R;
import de.blinkt.openvpn.VpnProfile;
import de.blinkt.openvpn.core.ConfigParser;
import de.blinkt.openvpn.core.ConnectionStatus;
import de.blinkt.openvpn.core.IOpenVPNServiceInternal;
import de.blinkt.openvpn.core.IServiceStatus;
import de.blinkt.openvpn.core.OpenVPNService;
import de.blinkt.openvpn.core.OpenVPNStatusService;
import de.blinkt.openvpn.core.PasswordCache;
import de.blinkt.openvpn.core.ProfileManager;
import de.blinkt.openvpn.core.VPNLaunchHelper;
import de.blinkt.openvpn.core.VpnStatus;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;

public final class PaperlineActivity extends Activity implements VpnStatus.StateListener {
    private final DialogMorph dialogMorph = new DialogMorph(this);
    private final ThemeTransition colors = new ThemeTransition();
    private static final GlassTheme LIGHT = ThemeTransition.LIGHT, DARK = ThemeTransition.DARK;
    private TextView settingsButton;
    private static final PathInterpolator EASE = new PathInterpolator(0.22f, 1f, 0.36f, 1f);
    private static final OvershootInterpolator SPRING = new OvershootInterpolator(1.15f);
    private static final int CONNECT_BAR_HEIGHT = 62;
    private static final int PREVIEW_RELAYS = 12;
    private static final String LAST_GLOBAL_COUNTRY = "last_global_country";
    private static final int PICK_PROFILE = 72;
    private static final int VPN_PERMISSION = 73;
    private static final int VPN_NOTIFICATION_PERMISSION = 74;
    private static final long PROFILE_TIMEOUT_MS = 8_000;
    private static final long TUNNEL_TIMEOUT_MS = 12_000;
    private static final long NEGOTIATION_GRACE_MS = 6_000;
    private static final long QUICK_STARTUP_TIMEOUT_MS = 25_000;
    private static final long QUICK_SHUTDOWN_TIMEOUT_MS = 10_000;
    private static final String PAKISTAN_ACCOUNT = "https://www.vpnjantit.com/create-free-account?server=pk";
    private static final String PAKISTAN_CONFIG = "https://www.vpnjantit.com/download-openvpn.php?server=pk";

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService work = Executors.newFixedThreadPool(2);
    private final Set<String> expanded = new LinkedHashSet<>();
    private final Set<String> showingAllRelays = new LinkedHashSet<>();
    private final Map<String,TextView> countryActions = new HashMap<>();
    private final List<Imported> imported = new ArrayList<>();
    private final LruCache<String, Bitmap> countryIcons = new LruCache<>(96);
    private Typeface actionTypeface;
    private VpnDirectory directory;
    private VpnDirectory.Edition edition = new VpnDirectory.Edition();
    private LinearLayout locations;
    private GlassTheme theme;
    private GlassRefreshScroll scroll;
    private TextView state, detail, action;
    private AlertDialog settings;
    private TextView relayCount, relayCountries, directoryVersion;
    private ConnectionMeter meter;
    private ValueAnimator connectionMotion;
    private EditText search;
    private IOpenVPNServiceInternal vpn;
    private IServiceStatus engineStatus;
    private Future<?> profileTask;
    private QuickSession quick;
    private String activeProfileId;
    private boolean foreground, awaitingPermission, dialStarted;
    private VpnProfile pending;
    private boolean statusBound, waitingForEngine, engineReplied;
    private boolean bound, allCountries, refreshing, visibleRefresh, busy, connected, started, stopping, startedObserved;
    private int connectAttempt;
    private int tunnelCheck;
    private String message = "";
    private String importCountry = "";
    private String displayName = "";
    private String displayCountry = "";

    private final ServiceConnection vpnConnection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder binder) {
            vpn = IOpenVPNServiceInternal.Stub.asInterface(binder);
        }

        @Override public void onServiceDisconnected(ComponentName name) {
            vpn = null;
            if (stopping) clearStopped();
            else if (started && quick != null) clearStopped();
            else if (started) fail("The VPN service stopped. Try connecting again.");
        }
    };

    private final ServiceConnection statusConnection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder binder) {
            engineStatus = IServiceStatus.Stub.asInterface(binder);
            if (waitingForEngine) startTunnel();
        }

        @Override public void onServiceDisconnected(ComponentName name) { engineStatus = null; }
    };

    @Override
    public void onCreate(Bundle saved) {
        super.onCreate(saved);
        if (getPreferences(MODE_PRIVATE).contains("fingerprint_lock"))
            getPreferences(MODE_PRIVATE).edit().remove("fingerprint_lock").apply();
        boolean systemDark = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
        theme = getPreferences(MODE_PRIVATE).getBoolean("dark_theme",systemDark) ? DARK : LIGHT;
        colors.initialize(theme.dark);
        allCountries = getPreferences(MODE_PRIVATE).getBoolean("all_countries",false);
        if (saved != null) {
            importCountry = saved.getString("importCountry","");
            ArrayList<String> open = saved.getStringArrayList("expanded");
            if (open != null) expanded.addAll(open);
        }
        directory = new VpnDirectory(this);
        edition = directory.starter();
        displayName = getPreferences(MODE_PRIVATE).getString("last_name", "");
        displayCountry = getPreferences(MODE_PRIVATE).getString("last_country", "");
        buildScreen(saved == null ? "" : saved.getString("query",""),
                saved == null ? 0 : saved.getInt("scrollY"),saved == null);
        work.execute(() -> {
            VpnDirectory.Edition savedEdition = directory.cached();
            List<Imported> profiles = loadImports();
            main.post(() -> {
                if (isDestroyed()) return;
                if (!refreshing && savedEdition.total > 0 && savedEdition.sourceTime >= edition.sourceTime) {
                    edition = savedEdition;
                }
                imported.clear();
                imported.addAll(profiles);
                renderLocations();
                renderState();
                updateDirectoryDetails();
                if (quick != null && quick.rankedEdition == null) advanceQuickConnect();
                if (directory.needsRefresh(edition)) refreshDirectory();
            });
        });
    }

    @Override protected void onStart() {
        super.onStart();
        foreground = true;
        VpnStatus.addStateListener(this);
        bindController();
        statusBound = bindService(new Intent(this,OpenVPNStatusService.class), statusConnection, Context.BIND_AUTO_CREATE);
        advanceQuickConnect();
        animateConnectionMeter();
    }

    private void bindController() {
        if (bound) return;
        Intent request = new Intent(this,OpenVPNService.class).setAction(OpenVPNService.START_SERVICE);
        bound = bindService(request,vpnConnection,Context.BIND_AUTO_CREATE);
    }

    @Override protected void onStop() {
        foreground = false;
        animateConnectionMeter();
        VpnStatus.removeStateListener(this);
        unbindController();
        if (statusBound) {
            unbindService(statusConnection);
            statusBound = false;
            engineStatus = null;
        }
        super.onStop();
    }

    private void unbindController() {
        if (bound) {
            unbindService(vpnConnection);
            bound = false;
            vpn = null;
        }
    }

    @Override protected void onDestroy() {
        colors.cancel();
        dialogMorph.dispose();
        if (settings != null) settings.dismiss();
        work.shutdownNow();
        main.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        state.putString("importCountry",importCountry);
        state.putStringArrayList("expanded",new ArrayList<>(expanded));
        state.putString("query",search.getText().toString());
        state.putInt("scrollY",scroll.getScrollY());
    }

    private void changeTheme(AlertDialog menu,boolean dark) {
        theme = dark ? DARK : LIGHT;
        getPreferences(MODE_PRIVATE).edit().putBoolean("dark_theme",dark).apply();
        colors.animate(dark,getWindow().getDecorView(),menu.getWindow().getDecorView(),() -> {
            applySystemBars();
            if (menu.isShowing()) dialogMorph.syncBarAppearance(menu.getWindow());
        });
    }

    private void applySystemBars() {
        Window window = getWindow();
        View decor = window.getDecorView();
        window.setBackgroundDrawable(new ColorDrawable(theme.base));
        window.setStatusBarColor(Color.TRANSPARENT);
        window.setNavigationBarColor(Color.TRANSPARENT);
        if (Build.VERSION.SDK_INT >= 29) window.setNavigationBarContrastEnforced(false);
        if (Build.VERSION.SDK_INT >= 30) {
            int lightBars = android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                    | android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
            android.view.WindowInsetsController controller = window.getInsetsController();
            if (controller != null) controller.setSystemBarsAppearance(theme.dark ? 0 : lightBars,lightBars);
        }
        int flags = View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION;
        if (!theme.dark) flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        decor.setSystemUiVisibility(flags);
    }

    private void buildScreen(String query,int scrollY,boolean opening) {
        applySystemBars();
        FrameLayout root = new FrameLayout(this);
        ThemeTransition.Blended backdrop = new ThemeTransition.Blended(LIGHT.backdrop(),DARK.backdrop(),true);
        colors.background(root,backdrop,backdrop::setNight);
        GlassRefreshScroll viewport = new GlassRefreshScroll(this,colors,this::pullRefresh);
        scroll = viewport;
        viewport.setFillViewport(true);
        viewport.setClipToPadding(false);
        viewport.setVerticalScrollBarEnabled(false);
        FrameLayout.LayoutParams viewportLayout = new FrameLayout.LayoutParams(-1,-1);
        viewportLayout.bottomMargin = dp(CONNECT_BAR_HEIGHT);
        root.addView(viewport,viewportLayout);
        LinearLayout sheet = column();
        sheet.setPadding(dp(19),dp(10),dp(19),dp(16));
        viewport.addView(sheet,new ScrollView.LayoutParams(-1,-2));

        FrameLayout mast = new FrameLayout(this);
        TextView title = text("Setu VPN",36,theme.ink,"sans-serif-medium",Typeface.NORMAL);
        title.setLetterSpacing(-0.055f);
        title.setSingleLine(true);
        title.setGravity(Gravity.CENTER);
        title.setAutoSizeTextTypeUniformWithConfiguration(19,36,1,android.util.TypedValue.COMPLEX_UNIT_SP);
        FrameLayout.LayoutParams titleLayout = new FrameLayout.LayoutParams(-1,-1,Gravity.CENTER);
        titleLayout.setMargins(dp(81),0,dp(81),0);
        mast.addView(title,titleLayout);
        settingsButton = button("Settings",false);
        settingsButton.setPadding(dp(6),0,dp(6),0);
        settingsButton.setSingleLine(true);
        settingsButton.setAutoSizeTextTypeUniformWithConfiguration(11,14,1,android.util.TypedValue.COMPLEX_UNIT_SP);
        settingsButton.setOnClickListener(v -> showSettings(true));
        mast.addView(settingsButton,new FrameLayout.LayoutParams(dp(78),dp(45),Gravity.END | Gravity.CENTER_VERTICAL));
        sheet.addView(mast,new LinearLayout.LayoutParams(-1,dp(54)));
        gap(sheet,18);

        LinearLayout card = column();
        card.setPadding(dp(18),dp(16),dp(18),dp(15));
        colorGlass(card,GlassTheme.Surface.PANEL,30);
        colors.bind(card,night -> card.setElevation(dp(3) * (1f - night)));

        LinearLayout words = column();
        words.setGravity(Gravity.CENTER);
        words.setMinimumHeight(dp(80));
        LabelText connection = new LabelText("CONNECTION",10,theme.muted);
        connection.setGravity(Gravity.CENTER);
        words.addView(connection);
        state = text("Disconnected",29,Color.WHITE,"sans-serif-medium",Typeface.NORMAL, false);
        colorText(state,LIGHT.ink,Color.WHITE);
        state.setGravity(Gravity.CENTER);
        state.setLetterSpacing(-0.045f);
        words.addView(state);
        detail = text("Choose a location or connect to a relay.",13,theme.muted,"sans-serif",Typeface.NORMAL);
        detail.setPadding(0,dp(4),0,0);
        detail.setGravity(Gravity.CENTER);
        words.addView(detail);
        card.addView(words,new LinearLayout.LayoutParams(-1,-2));

        gap(card,11);
        meter = new ConnectionMeter();
        colors.bind(meter,night -> meter.invalidate());
        card.addView(meter,new LinearLayout.LayoutParams(-1,dp(3)));
        sheet.addView(card);
        gap(sheet,16);

        search = new EditText(inputContext());
        colorText(search,LIGHT.ink,DARK.ink);
        colors.bind(search,night -> search.setHintTextColor(ThemeTransition.color(LIGHT.muted,DARK.muted,night)));
        search.setHint("Search countries");
        search.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH);
        search.setSingleLine();
        search.setTextSize(15);
        search.setText(query);
        search.setSelection(search.length());
        colorGlass(search,GlassTheme.Surface.CONTROL,23);
        GlassTheme.SearchIcon icon = new GlassTheme.SearchIcon(theme.muted);
        icon.setBounds(0,0,dp(20),dp(20));
        search.setCompoundDrawablesRelative(icon,null,null,null);
        colors.bind(search,night -> icon.setTintColor(ThemeTransition.color(LIGHT.muted,DARK.muted,night)));
        search.setCompoundDrawablePadding(dp(10));
        search.setPadding(dp(14),0,dp(14),0);
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s,int start,int count,int after) {}
            @Override public void onTextChanged(CharSequence s,int start,int before,int count) {
                renderLocations();
                reveal(locations,3,170,0.8f,0);
            }
            @Override public void afterTextChanged(Editable s) {}
        });
        search.setOnEditorActionListener((view,actionId,event) -> {
            hideKeyboard();
            return true;
        });
        sheet.setFocusableInTouchMode(true);
        sheet.addView(search,new LinearLayout.LayoutParams(-1,dp(50)));
        sheet.requestFocus();
        gap(sheet,12);
        locations = column();
        sheet.addView(locations);

        FrameLayout dock = new FrameLayout(this);
        float barRadius = dp(30);
        ThemeTransition.Blended dockSurface = new ThemeTransition.Blended(
                LIGHT.bottomBar(barRadius,dp(1)),DARK.bottomBar(barRadius,dp(1)),false);
        colors.background(dock,dockSurface,dockSurface::setNight);
        action = actionLabel("Quick Connect",16,theme.accent);
        action.setSingleLine(true);
        action.setPadding(dp(12),0,dp(12),0);
        action.setMinimumWidth(dp(48));
        action.setOnClickListener(this::heroAction);
        dock.addView(action,new FrameLayout.LayoutParams(-2,dp(48),Gravity.CENTER));
        root.addView(dock,new FrameLayout.LayoutParams(-1,dp(CONNECT_BAR_HEIGHT),Gravity.BOTTOM));
        View indicator = viewport.indicator();
        root.addView(indicator,new FrameLayout.LayoutParams(-2,dp(38),Gravity.TOP | Gravity.CENTER_HORIZONTAL));
        addWindowInsets(root,viewport,dock);

        setContentView(root);
        root.requestApplyInsets();
        renderLocations();
        renderState();
        viewport.setRefreshing(visibleRefresh);
        viewport.post(() -> viewport.scrollTo(0,scrollY));
        reveal(mast,7,275,0.48f,0);
        reveal(card,12,330,0.40f,45);
        reveal(search,7,275,0.55f,120);
        reveal(locations,8,290,0.56f,170);
        reveal(action,0,240,0.66f,100);
        if (opening && ValueAnimator.areAnimatorsEnabled()) showOpeningAnimation(root);
    }

    private void showOpeningAnimation(FrameLayout root) {
        FrameLayout cover = new FrameLayout(this);
        cover.setBackground(theme.backdrop());
        cover.setTranslationZ(dp(16));
        cover.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        ImageView emblem = new ImageView(this);
        emblem.setScaleType(ImageView.ScaleType.FIT_CENTER);
        emblem.setPadding(dp(12),dp(12),dp(12),dp(12));
        try (InputStream image = getAssets().open("setu-logo.png")) {
            emblem.setImageBitmap(BitmapFactory.decodeStream(image));
        } catch (IOException ignored) {
            // The launch animation can finish if the image is unavailable.
        }
        emblem.setBackground(DARK.surface(GlassTheme.Surface.ORB,dp(54),rim()));
        emblem.setScaleX(.72f);
        emblem.setScaleY(.72f);
        cover.addView(emblem,new FrameLayout.LayoutParams(dp(104),dp(104),Gravity.CENTER));
        root.addView(cover,new FrameLayout.LayoutParams(-1,-1));
        cover.postOnAnimation(() -> {
            if (!cover.isAttachedToWindow()) return;
            emblem.animate().scaleX(1f).scaleY(1f).setInterpolator(SPRING).setDuration(390).start();
            cover.animate().alpha(0f).setStartDelay(230).setDuration(265).setInterpolator(EASE)
                    .withEndAction(() -> root.removeView(cover)).start();
        });
    }

    private void addWindowInsets(FrameLayout root,GlassRefreshScroll viewport,FrameLayout dock) {
        root.setOnApplyWindowInsetsListener((view,insets) -> {
            int top = insets.getSystemWindowInsetTop();
            int bottom = insets.getSystemWindowInsetBottom();
            int left = insets.getSystemWindowInsetLeft();
            int right = insets.getSystemWindowInsetRight();
            int keyboardBottom = 0;
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets safe = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                top = safe.top;
                bottom = safe.bottom;
                left = safe.left;
                right = safe.right;
                keyboardBottom = insets.getInsets(WindowInsets.Type.ime()).bottom;
            } else if (bottom > dp(96)) {
                keyboardBottom = bottom;
            }
            boolean typing = keyboardBottom > 0 && keyboardBottom >= bottom;
            dock.setVisibility(typing ? View.GONE : View.VISIBLE);
            int barHeight = dp(CONNECT_BAR_HEIGHT) + bottom;
            FrameLayout.LayoutParams scrollLayout = (FrameLayout.LayoutParams) viewport.getLayoutParams();
            int reserved = typing ? 0 : barHeight;
            if (scrollLayout.bottomMargin != reserved) {
                scrollLayout.bottomMargin = reserved;
                viewport.setLayoutParams(scrollLayout);
            }
            viewport.setPadding(left,top,right,typing ? keyboardBottom : 0);
            View indicator = viewport.indicator();
            FrameLayout.LayoutParams indicatorLayout = (FrameLayout.LayoutParams) indicator.getLayoutParams();
            if (indicatorLayout.topMargin != top + dp(6) || indicatorLayout.leftMargin != left
                    || indicatorLayout.rightMargin != right) {
                indicatorLayout.setMargins(left,top + dp(6),right,0);
                indicator.setLayoutParams(indicatorLayout);
            }
            FrameLayout.LayoutParams dockLayout = (FrameLayout.LayoutParams) dock.getLayoutParams();
            if (dockLayout.height != barHeight) {
                dockLayout.height = barHeight;
                dock.setLayoutParams(dockLayout);
            }
            dock.setPadding(left,0,right,bottom);
            return insets;
        });
    }

    private void setAllCountries(boolean showAll) {
        allCountries = showAll;
        getPreferences(MODE_PRIVATE).edit().putBoolean("all_countries",showAll).apply();
        renderLocations();
        reveal(locations,showAll ? 5 : -5,250,0.7f,0);
    }

    private void pullRefresh() {
        visibleRefresh = true;
        scroll.setRefreshing(true);
        scroll.announceForAccessibility("Updating server directory");
        refreshDirectory();
    }

    private void refreshDirectory() {
        if (refreshing) return;
        refreshing = true;
        work.execute(() -> {
            VpnDirectory.Edition update = null;
            try { update = directory.refresh(); }
            catch (Exception ignored) {
                // The app can still use its last successful directory or an imported profile.
            }
            VpnDirectory.Edition result = update;
            main.post(() -> {
                if (isDestroyed()) return;
                refreshing = false;
                boolean requested = visibleRefresh;
                visibleRefresh = false;
                scroll.setRefreshing(false);
                if (result != null) edition = result;
                else if (requested) toast("Directory refresh failed. Showing the last available list.");
                if (requested && result != null) scroll.announceForAccessibility("Server directory updated");
                if (quick != null && quick.rankedEdition == null) {
                    if (edition.best.isEmpty()) fail("No servers are available right now.");
                    else advanceQuickConnect();
                }
                renderLocations();
                updateDirectoryDetails();
                if (result != null) reveal(locations,7,275,0.72f,0);
                renderState();
            });
        });
    }

    private void renderLocations() {
        if (locations == null) return;
        locations.removeAllViews();
        countryActions.clear();
        String term = search.getText().toString().trim().toLowerCase(Locale.ROOT);
        List<String> codes = new ArrayList<>(locationCodes(allCountries || !term.isEmpty()));
        codes.sort(Comparator.comparingInt((String code) -> listedRelays(code).size()).reversed());
        int shown = 0;
        for (String code : codes) {
            String name = VpnDirectory.name(code);
            if (!term.isEmpty() && !code.equalsIgnoreCase(term) && !name.toLowerCase(Locale.ROOT).contains(term)) continue;
            List<VpnDirectory.Relay> entries = listedRelays(code);
            List<Imported> local = importsFor(code);
            if (allCountries && entries.isEmpty() && local.isEmpty()) continue;
            countryRow(code,name,entries,local);
            shown++;
        }
        List<Imported> uncategorized = importsFor("");
        if (!uncategorized.isEmpty() && term.isEmpty()) {
            countryRow("", "Imported profiles", Collections.emptyList(), uncategorized);
            shown++;
        }
        if (shown == 0) paragraph(locations,"No matching locations.");
    }

    private List<VpnDirectory.Relay> listedRelays(String code) {
        return edition.countries.getOrDefault(code,Collections.emptyList());
    }

    private Set<String> locationCodes(boolean showAll) {
        Set<String> codes = new LinkedHashSet<>(Arrays.asList(VpnDirectory.PRIORITY));
        if (!showAll) {
            codes.addAll(Arrays.asList(VpnDirectory.FEATURED));
            return codes;
        }
        List<String> rest = new ArrayList<>(Arrays.asList(VpnDirectory.FEATURED));
        rest.addAll(edition.countries.keySet());
        rest.addAll(directory.providerCountries());
        for (Imported profile : imported) if (!profile.country.isEmpty()) rest.add(profile.country);
        rest.sort(Comparator.comparing(VpnDirectory::name));
        codes.addAll(rest);
        return codes;
    }

    private List<Imported> importsFor(String code) {
        List<Imported> result = new ArrayList<>();
        for (Imported t : imported) if (code.equals(t.country)) result.add(t);
        return result;
    }

    private void countryRow(String code, String name, List<VpnDirectory.Relay> entries, List<Imported> local) {
        LinearLayout item = column();
        colorGlass(item,GlassTheme.Surface.PANEL,23);
        item.setClipToOutline(true);
        LinearLayout heading = row();
        heading.setMinimumHeight(dp(64));
        heading.setPadding(dp(12),dp(2),dp(9),dp(2));
        ImageView flag = new ImageView(this);
        flag.setScaleType(ImageView.ScaleType.FIT_CENTER);
        flag.setImageBitmap(countryIcon(code));
        flag.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        heading.addView(flag,new LinearLayout.LayoutParams(dp(40),dp(36)));
        LinearLayout labels = column();
        labels.setPadding(dp(12),dp(6),dp(5),dp(6));
        TextView countryName = text(name,17,theme.ink,"sans-serif-medium",Typeface.NORMAL);
        labels.addView(countryName);
        final String caption;
        if (entries.isEmpty() && local.isEmpty()) caption = "PK".equals(code)
                ? "2 servers · free login required" : "No server listed";
        else {
            String relayCount = entries.isEmpty() ? "" : entries.size() + (entries.size() == 1 ? " relay listed" : " relays listed");
            caption = local.isEmpty() ? relayCount : relayCount + (relayCount.isEmpty() ? "" : " · ") + local.size() + " imported";
        }
        labels.addView(text(caption,12,theme.muted,"sans-serif",Typeface.NORMAL));
        heading.addView(labels,weighted());
        TextView arrow = text("›",25,theme.muted,"sans-serif",Typeface.NORMAL);
        arrow.setGravity(Gravity.CENTER);
        arrow.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        heading.addView(arrow,new LinearLayout.LayoutParams(dp(27),dp(35)));
        heading.setFocusable(true);
        boolean isOpen = expanded.contains(code);
        updateCountryHeading(heading,arrow,name,caption,isOpen,false);
        heading.setOnClickListener(view -> {
            hideKeyboard();
            boolean opening = expanded.add(code);
            boolean fullList = !opening && showingAllRelays.remove(code);
            if (!opening) expanded.remove(code);
            updateCountryHeading(heading,arrow,name,caption,opening,true);
            CountryPanel panel = item.getChildCount() > 1 ? (CountryPanel) item.getChildAt(1) : null;
            if (panel == null && opening) {
                panel = countryPanel(code,name,entries,local,heading);
                item.addView(panel,new LinearLayout.LayoutParams(-1,0));
            }
            if (panel != null) {
                if (fullList) {
                    returnToHeading(heading);
                    item.removeView(panel);
                } else panel.slide(opening);
            }
        });
        item.addView(heading);
        if (isOpen) item.addView(countryPanel(code,name,entries,local,heading));
        LinearLayout.LayoutParams itemLayout = new LinearLayout.LayoutParams(-1,-2);
        itemLayout.bottomMargin = dp(8);
        locations.addView(item,itemLayout);
    }

    private Bitmap countryIcon(String code) {
        Bitmap cached = countryIcons.get(code);
        if (cached != null) return cached;
        boolean country = code.matches("[A-Z]{2}");
        String glyph = country ? new String(new int[]{
                0x1f1e6 + code.charAt(0) - 'A',
                0x1f1e6 + code.charAt(1) - 'A'},0,2)
                : code.isEmpty() ? "📄" : "🌐";
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setTextSize(64);
        if (!paint.hasGlyph(glyph)) glyph = "🌐";
        int width = Math.max(1,(int)Math.ceil(paint.measureText(glyph)));
        int height = (int)Math.ceil(paint.descent() - paint.ascent());
        Bitmap bitmap = Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888);
        new Canvas(bitmap).drawText(glyph,0,-paint.ascent(),paint);
        if (country) bitmap = FlagArtwork.light(bitmap);
        countryIcons.put(code,bitmap);
        return bitmap;
    }

    private void updateCountryHeading(View heading,View arrow,String name,String caption,boolean open,boolean animate) {
        heading.setContentDescription(name + ". " + caption + ". " + (open ? "Collapse." : "Expand."));
        float angle = open ? 90f : 0f;
        arrow.animate().cancel();
        if (animate && ValueAnimator.areAnimatorsEnabled()) {
            arrow.animate().rotation(angle).setInterpolator(SPRING).setDuration(310).start();
        } else arrow.setRotation(angle);
    }

    private void returnToHeading(View heading) {
        if (heading.getGlobalVisibleRect(new android.graphics.Rect())) return;
        int[] anchor = new int[2], viewport = new int[2];
        heading.getLocationInWindow(anchor);
        scroll.getLocationInWindow(viewport);
        int position = scroll.getScrollY() + anchor[1] - viewport[1] - scroll.getPaddingTop();
        scroll.post(() -> scroll.scrollTo(0,position));
    }

    private CountryPanel countryPanel(String code,String name,List<VpnDirectory.Relay> entries,
                                      List<Imported> local,View heading) {
        CountryPanel panel = new CountryPanel(code);
        populateCountryPanel(panel,code,name,entries,local,heading);
        return panel;
    }

    private void populateCountryPanel(CountryPanel children,String code,String name,List<VpnDirectory.Relay> entries,
                                      List<Imported> local,View heading) {
        boolean canQuickConnect = !entries.isEmpty() || local.stream().anyMatch(profile -> !profile.needsLogin());
        if (!code.isEmpty() && (canQuickConnect || ownsCountryAction(code))) {
            TextView toggle = button("Quick Connect",true);
            toggle.setOnClickListener(view -> countryQuickAction(code,view));
            LinearLayout.LayoutParams layout = new LinearLayout.LayoutParams(-1,dp(45));
            layout.bottomMargin = dp(7);
            children.addView(toggle,layout);
            children.quickButton = toggle;
            countryActions.put(code,toggle);
            renderCountryAction(code,toggle);
        }
        for (Imported profile : local) {
            LinearLayout tile = addTile(children, profile.name, "Imported OpenVPN profile", "Connect",
                    view -> connectImport(profile,view));
            tile.setOnLongClickListener(v -> { deleteImport(profile,v); return true; });
        }
        if ("PK".equals(code)) {
            for (int number = 1; number <= 2; number++) {
                int server = number;
                addTile(children,"Pakistan " + server,"Karachi · free login required","Set up",
                        view -> pakistanDialog(server,view));
            }
        }
        if (showingAllRelays.contains(code) && entries.size() > PREVIEW_RELAYS) {
            addHideServers(children,heading);
            children.addView(new RelayList(code,name,entries),new LinearLayout.LayoutParams(-1,-2));
            addHideServers(children,heading);
        } else {
            int limit = Math.min(entries.size(),PREVIEW_RELAYS);
            for (int i = 0; i < limit; i++) {
                VpnDirectory.Relay relay = entries.get(i);
                String label = relayLabel(code,name,i);
                addTile(children,label,relay.caption(),"Connect",view -> connectRelay(relay,label,view));
            }
            if (entries.size() > limit) {
                TextView more = button(String.format(Locale.getDefault(),"Show all %,d servers",entries.size()),false);
                more.setOnClickListener(view -> {
                    showingAllRelays.add(code);
                    children.cancelSlide();
                    children.removeAllViews();
                    populateCountryPanel(children,code,name,entries,local,heading);
                    children.settle(true,(LinearLayout)children.getParent());
                });
                children.addView(more);
            } else if (limit > 0 || !local.isEmpty() || "PK".equals(code)) addHideServers(children,heading);
        }
        if (entries.isEmpty() && local.isEmpty() && !"PK".equals(code))
            paragraph(children, "There is no server in the current list.");
    }

    private void addHideServers(LinearLayout parent,View heading) {
        TextView hide = button("Hide servers",false);
        hide.setOnClickListener(view -> heading.performClick());
        parent.addView(hide);
    }

    private static String relayLabel(String code,String name,int index) {
        return name + " " + (index + ("PK".equals(code) ? 3 : 1));
    }

    private LinearLayout addTile(LinearLayout parent,String title,String subtitle,String verb,Consumer<View> click) {
        ServerTile tile = new ServerTile(verb);
        tile.bind(title,subtitle,click);
        parent.addView(tile);
        return tile;
    }

    private final class ServerTile extends LinearLayout {
        private final TextView title, subtitle, action;

        ServerTile(String verb) {
            super(PaperlineActivity.this);
            setOrientation(HORIZONTAL);
            setGravity(Gravity.CENTER_VERTICAL);
            setMinimumHeight(dp(53));
            LinearLayout descriptions = column();
            descriptions.setPadding(dp(4),dp(5),dp(6),dp(5));
            title = text("",15,theme.ink,"sans-serif-medium",Typeface.NORMAL);
            subtitle = text("",12,theme.muted,"sans-serif",Typeface.NORMAL);
            descriptions.addView(title);
            descriptions.addView(subtitle);
            addView(descriptions,weighted());
            action = button(verb,false);
            action.setMinimumWidth(dp(75));
            addView(action);
        }

        void bind(String name,String caption,Consumer<View> click) {
            title.setText(name);
            subtitle.setText(caption);
            action.setOnClickListener(click::accept);
        }
    }

    /** Keeps the full list in the page scroll, with views only for the visible relay rows. */
    private final class RelayList extends ViewGroup {
        private final String country, name;
        private final List<VpnDirectory.Relay> entries;
        private final SparseArray<ServerTile> visibleRows = new SparseArray<>();
        private final ArrayDeque<ServerTile> recycled = new ArrayDeque<>();
        private final int[] localPosition = new int[2], scrollPosition = new int[2];
        private final ServerTile sizingRow = new ServerTile("Connect");
        private final ViewTreeObserver.OnScrollChangedListener onScroll = this::layoutRows;
        private ViewTreeObserver observer;
        private int rowHeight;
        private int lastFirst = -1, lastEnd = -1, lastWidth, lastRowHeight;

        RelayList(String country,String name,List<VpnDirectory.Relay> entries) {
            super(PaperlineActivity.this);
            this.country = country;
            this.name = name;
            this.entries = entries;
            sizingRow.bind(relayLabel(country,name,entries.size() - 1),
                    "TCP answered · 100000 Mbps reported",view -> {});
        }

        @Override protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            observer = getViewTreeObserver();
            observer.addOnScrollChangedListener(onScroll);
        }

        @Override protected void onDetachedFromWindow() {
            if (observer != null && observer.isAlive()) observer.removeOnScrollChangedListener(onScroll);
            observer = null;
            super.onDetachedFromWindow();
        }

        @Override protected void onMeasure(int widthSpec,int heightSpec) {
            int width = MeasureSpec.getSize(widthSpec);
            sizingRow.measure(MeasureSpec.makeMeasureSpec(width,MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(0,MeasureSpec.UNSPECIFIED));
            rowHeight = sizingRow.getMeasuredHeight();
            setMeasuredDimension(width,rowHeight * entries.size());
        }

        @Override protected void onLayout(boolean changed,int left,int top,int right,int bottom) {
            layoutRows();
        }

        private void layoutRows() {
            if (rowHeight <= 0 || getWidth() <= 0 || !isAttachedToWindow()) return;
            getLocationInWindow(localPosition);
            scroll.getLocationInWindow(scrollPosition);
            int viewportTop = scrollPosition[1] - localPosition[1];
            int viewportBottom = viewportTop + scroll.getHeight();
            int first = clampIndex(Math.floorDiv(viewportTop,rowHeight) - 2);
            int end = clampIndex(Math.floorDiv(viewportBottom,rowHeight) + 3);
            if (first == lastFirst && end == lastEnd && getWidth() == lastWidth && rowHeight == lastRowHeight)
                return;
            lastFirst = first;
            lastEnd = end;
            lastWidth = getWidth();
            lastRowHeight = rowHeight;
            for (int i = visibleRows.size() - 1; i >= 0; i--) {
                int index = visibleRows.keyAt(i);
                if (index >= first && index < end) continue;
                ServerTile tile = visibleRows.valueAt(i);
                removeViewInLayout(tile);
                visibleRows.removeAt(i);
                recycled.addLast(tile);
            }
            int width = MeasureSpec.makeMeasureSpec(getWidth(),MeasureSpec.EXACTLY);
            int height = MeasureSpec.makeMeasureSpec(rowHeight,MeasureSpec.EXACTLY);
            for (int index = first; index < end; index++) {
                ServerTile tile = visibleRows.get(index);
                if (tile == null) {
                    tile = recycled.pollFirst();
                    if (tile == null) tile = new ServerTile("Connect");
                    VpnDirectory.Relay relay = entries.get(index);
                    String label = relayLabel(country,name,index);
                    tile.bind(label,relay.caption(),view -> connectRelay(relay,label,view));
                    int childIndex = 0;
                    while (childIndex < visibleRows.size() && visibleRows.keyAt(childIndex) < index) childIndex++;
                    addViewInLayout(tile,childIndex,new ViewGroup.LayoutParams(-1,rowHeight),true);
                    visibleRows.put(index,tile);
                }
                tile.measure(width,height);
                int top = index * rowHeight;
                tile.layout(0,top,getWidth(),top + rowHeight);
            }
            invalidate();
        }

        private int clampIndex(int index) {
            return Math.max(0,Math.min(index,entries.size()));
        }
    }

    private void showSettings(boolean animateOpening) {
        if (settings != null && settings.isShowing()) return;
        if (search.hasFocus()) hideKeyboard();
        LinearLayout content = column();
        content.setPadding(dp(16),dp(5),dp(16),dp(12));
        ScrollView body = new ScrollView(this);
        body.setVerticalScrollBarEnabled(false);
        body.addView(content,new ScrollView.LayoutParams(-1,-2));
        TextView title = text("Settings",20,theme.ink,"sans-serif-medium",Typeface.NORMAL);
        title.setPadding(dp(24),dp(20),dp(24),dp(10));
        if (Build.VERSION.SDK_INT >= 28) title.setAccessibilityHeading(true);
        AlertDialog menu = dialogBuilder().setCustomTitle(title).setView(body)
                .setPositiveButton("Done",null).create();
        settings = menu;
        menu.setOnDismissListener(dialog -> {
            if (settings != menu) return;
            settings = null;
            relayCount = null;
            relayCountries = null;
            directoryVersion = null;
        });

        populateSettings(menu,content);
        showAnchoredDialog(menu,DialogMorph.origin(settingsButton),animateOpening);
        dialogMorph.bindPalette(menu,colors);
        menu.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(
                view -> closeDialog(menu,DialogMorph.origin(settingsButton),null));
        bindDialogBack(menu,() -> closeDialog(menu,DialogMorph.origin(settingsButton),null));
    }

    private void populateSettings(AlertDialog menu,LinearLayout content) {
        LinearLayout preferences = settingsCard(content);
        Switch darkMode = settingsSwitch(preferences,"Dark mode",theme.dark);
        darkMode.setOnCheckedChangeListener((button,checked) -> changeTheme(menu,checked));
        rule(preferences,theme.divider,1,0);
        Switch countries = settingsSwitch(preferences,"Show all countries",allCountries);
        countries.setOnCheckedChangeListener((button,checked) -> setAllCountries(checked));

        gap(content,10);
        LinearLayout actions = settingsCard(content);
        settingsAction(actions,"Import OpenVPN profile or ZIP",view -> {
            DialogMorph.Origin origin = DialogMorph.origin(view);
            chooseImportCountry(origin);
        });
        rule(actions,theme.divider,1,0);
        settingsAction(actions,"About Setu VPN",view -> {
            DialogMorph.Origin origin = DialogMorph.origin(view);
            showAbout(origin);
        });

        gap(content,10);
        LinearLayout directoryInfo = settingsCard(content);
        relayCount = settingsValue(directoryInfo,"Volunteer relays");
        rule(directoryInfo,theme.divider,1,0);
        relayCountries = settingsValue(directoryInfo,"Relay countries");
        rule(directoryInfo,theme.divider,1,0);
        directoryVersion = settingsValue(directoryInfo,"Directory version");
        rule(directoryInfo,theme.divider,1,0);
        settingsValue(directoryInfo,"App version").setText(appVersion());
        updateDirectoryDetails();

        TextView credit = text("MADE WITH ♥️ IN INDIA",12,theme.muted,"sans-serif",Typeface.NORMAL);
        credit.setGravity(Gravity.CENTER);
        credit.setPadding(0,dp(14),0,dp(3));
        content.addView(credit,new LinearLayout.LayoutParams(-1,-2));
    }

    private LinearLayout settingsCard(LinearLayout parent) {
        LinearLayout card = column();
        colorGlass(card,GlassTheme.Surface.PANEL,19);
        parent.addView(card,new LinearLayout.LayoutParams(-1,-2));
        return card;
    }

    private Switch settingsSwitch(LinearLayout parent,String title,boolean checked) {
        PillSwitch control = new PillSwitch(inputContext(),theme.dark);
        control.setBackground(null);
        control.setText(title);
        colorText(control,LIGHT.ink,DARK.ink);
        colors.bind(control,control::setNight);
        control.setTextSize(15);
        control.setPadding(dp(13),0,dp(13),0);
        control.setMinHeight(dp(50));
        control.setChecked(checked);
        parent.addView(control,new LinearLayout.LayoutParams(-1,-2));
        return control;
    }

    private void settingsAction(LinearLayout parent,String title,Consumer<View> launch) {
        TextView entry = actionLabel(title,15,theme.ink);
        entry.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        entry.setPadding(dp(13),0,dp(13),0);
        entry.setMinimumHeight(dp(49));
        entry.setOnClickListener(launch::accept);
        parent.addView(entry,new LinearLayout.LayoutParams(-1,-2));
    }

    private TextView settingsValue(LinearLayout parent,String title) {
        LinearLayout line = row();
        line.setPadding(dp(13),dp(8),dp(13),dp(8));
        line.setMinimumHeight(dp(43));
        line.addView(text(title,13,theme.muted,"sans-serif",Typeface.NORMAL),weighted());
        TextView value = text("",13,theme.ink,"sans-serif-medium",Typeface.NORMAL);
        line.addView(value);
        parent.addView(line,new LinearLayout.LayoutParams(-1,-2));
        return value;
    }

    private void updateDirectoryDetails() {
        if (relayCount == null || directoryVersion == null) return;
        relayCount.setText(String.format(Locale.getDefault(),"%,d",edition.total));
        relayCountries.setText(String.format(Locale.getDefault(),"%,d",edition.countries.size()));
        directoryVersion.setText(edition.version.isEmpty() ? "Unavailable" : edition.version);
    }

    private String appVersion() {
        try { return getPackageManager().getPackageInfo(getPackageName(),0).versionName; }
        catch (PackageManager.NameNotFoundException error) { return "Unavailable"; }
    }

    private void chooseImportCountry(DialogMorph.Origin origin) {
        List<String> codes = new ArrayList<>();
        codes.add("");
        codes.addAll(locationCodes(true));
        String[] names = new String[codes.size()];
        names[0] = "No country";
        for (int i = 1; i < names.length; i++) names[i] = VpnDirectory.name(codes.get(i));
        AlertDialog dialog = dialogBuilder().setTitle("Choose import location")
                .setItems(names,null).setNegativeButton("Back",null).create();
        showAnchoredDialog(dialog,origin,true);
        dialog.getListView().setSelector(android.R.color.transparent);
        dialog.getListView().setOnItemClickListener((parent,view,index,id) ->
                closeDialog(dialog,origin,() -> {
                    if (settings != null) settings.dismiss();
                    pickProfile(codes.get(index));
                }));
        bindDialogAction(dialog,AlertDialog.BUTTON_NEGATIVE,origin,() -> showSettings(false));
        bindDialogBack(dialog,() -> closeDialog(dialog,origin,() -> showSettings(false)));
    }

    private void showAbout(DialogMorph.Origin origin) {
        AlertDialog dialog = dialogBuilder().setTitle("About Setu VPN")
                .setMessage("Setu VPN uses OpenVPN for Android and VPN Gate’s volunteer relay directory. Listings and TCP checks can change, and a TCP response does not verify a VPN connection. VPN Gate relay operators can log connections.\n\nProvider profiles use their own terms and may require a free account. Import standalone .ovpn files or ZIPs containing them. Profiles must include their certificates. Imported profiles are stored on this device.")
                .setNeutralButton("VPN Gate policy",null).setPositiveButton("Back",null).create();
        showAnchoredDialog(dialog,origin,true);
        bindDialogAction(dialog,AlertDialog.BUTTON_NEUTRAL,origin,() -> browse("https://www.vpngate.net/en/about_abuse.aspx"));
        bindDialogAction(dialog,AlertDialog.BUTTON_POSITIVE,origin,() -> showSettings(false));
        bindDialogBack(dialog,() -> closeDialog(dialog,origin,() -> showSettings(false)));
    }

    private static final class QuickSession {
        final String country;
        final Set<String> tried = new LinkedHashSet<>();
        final ArrayDeque<VpnDirectory.Relay> remaining = new ArrayDeque<>();
        final ArrayDeque<Imported> imports;
        VpnDirectory.Edition rankedEdition;
        int attempts;

        QuickSession(String country,List<Imported> imports) {
            this.country = country;
            this.imports = new ArrayDeque<>(imports);
        }

        List<VpnDirectory.Relay> servers(VpnDirectory.Edition edition) {
            return country == null ? edition.best : edition.countries.getOrDefault(country,Collections.emptyList());
        }
    }

    private void heroAction(View view) {
        if (quick != null || connected || busy || started || stopping) {
            quick = null;
            if (stopping) {
                message = "Disconnected";
                renderState();
            } else stop("Disconnected");
            return;
        }
        List<Imported> ready = new ArrayList<>(imported);
        ready.removeIf(Imported::needsLogin);
        QuickSession session = new QuickSession(null,ready);
        if (edition.best.isEmpty() && !ready.isEmpty()) startQuickConnect(session);
        else withPublicRelayNotice("Quick Connect",view,() -> startQuickConnect(session));
    }

    private boolean ownsCountryAction(String code) {
        if (quick != null) return code.equals(quick.country);
        return code.equals(displayCountry) && (busy || connected || started || stopping);
    }

    private void countryQuickAction(String code,View view) {
        if (ownsCountryAction(code)) {
            heroAction(view);
            return;
        }
        if (connectionInProgress()) return;
        List<Imported> ready = importsFor(code);
        ready.removeIf(Imported::needsLogin);
        QuickSession session = new QuickSession(code,ready);
        String name = VpnDirectory.name(code);
        if (!session.servers(edition).isEmpty()) {
            withPublicRelayNotice("Quick Connect to " + name,view,() -> startQuickConnect(session));
        } else if (!ready.isEmpty()) startQuickConnect(session);
        else showAnchoredDialog(dialogBuilder().setTitle("No ready servers for " + name)
                    .setMessage("Pull down at the top to refresh the directory, or use Settings to import an OpenVPN profile.")
                    .setPositiveButton("Done",null).create(),DialogMorph.origin(view),true);
    }

    private void startQuickConnect(QuickSession session) {
        quick = session;
        busy = true;
        message = session.country == null ? "Choosing a server…"
                : "Finding the fastest server in " + VpnDirectory.name(session.country) + "…";
        renderState();
        advanceQuickConnect();
    }

    private void advanceQuickConnect() {
        QuickSession session = quick;
        if (session == null || !foreground || started || stopping || profileTask != null || awaitingPermission) return;
        if (pending != null) {
            prepare(pending,pendingPassword);
            return;
        }

        VpnDirectory.Relay relay = session.remaining.pollFirst();
        if (relay != null) {
            session.tried.add(relay.ip);
            session.attempts++;
            List<VpnDirectory.Relay> country = session.rankedEdition.countries.get(relay.country);
            int number = country.indexOf(relay) + ("PK".equals(relay.country) ? 3 : 1);
            String name = VpnDirectory.name(relay.country) + " " + number;
            loadProfile(name,relay.country,"","",() -> directory.profile(relay),"Couldn’t load this server.",null);
            return;
        }
        VpnDirectory.Edition snapshot = edition;
        List<VpnDirectory.Relay> servers = session.servers(snapshot);
        if (session.rankedEdition != snapshot && !servers.isEmpty()) {
            rankQuickServers(session,snapshot,servers);
            return;
        }
        if (session.country == null && session.rankedEdition == null && session.imports.isEmpty()) {
            refreshDirectory();
            return;
        }
        session.rankedEdition = snapshot;
        Imported profile = session.imports.pollFirst();
        if (profile != null) {
            session.attempts++;
            connectImport(profile,"","");
            return;
        }
        fail(session.country == null ? "Couldn’t connect to any listed server. Refresh and try again."
                : "Couldn’t connect in " + VpnDirectory.name(session.country) + ". Refresh and try again.");
    }

    private void rankQuickServers(QuickSession session,VpnDirectory.Edition snapshot,List<VpnDirectory.Relay> servers) {
        Set<String> tried = new LinkedHashSet<>(session.tried);
        boolean global = session.country == null;
        String previous = global ? getPreferences(MODE_PRIVATE).getString(LAST_GLOBAL_COUNTRY,"") : "";
        session.rankedEdition = snapshot;
        profileTask = work.submit(() -> {
            List<VpnDirectory.Relay> ordered = QuickConnectOrder.rank(
                    servers,tried,global,previous,ThreadLocalRandom.current().nextLong());
            try {
                if (global) QuickConnectOrder.measureFirstCountry(ordered);
                else QuickConnectOrder.measureLatency(ordered);
            }
            catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            }
            main.post(() -> {
                if (quick != session || isDestroyed()) return;
                profileTask = null;
                session.remaining.addAll(ordered);
                advanceQuickConnect();
            });
        });
    }

    private void connectRelay(VpnDirectory.Relay relay,String name,View view) {
        if (connectionInProgress()) return;
        withPublicRelayNotice("Connect to " + VpnDirectory.name(relay.country) + "?",view,() ->
                loadProfile(name,relay.country,"","",() -> directory.profile(relay),
                        "Couldn’t load this server. Try another one.",null));
    }

    private void withPublicRelayNotice(String title,View view,Runnable connect) {
        if (getPreferences(MODE_PRIVATE).getBoolean("public_notice",false)) {
            connect.run();
            return;
        }
        DialogMorph.Origin origin = DialogMorph.origin(view);
        AlertDialog dialog = dialogBuilder().setTitle(title)
                .setMessage("Setu VPN uses public VPN Gate relays. VPN Gate records connection logs. Relay operators can record packet headers.")
                .setNegativeButton("Cancel",null).setPositiveButton("Connect",null).create();
        showAnchoredDialog(dialog,origin,true);
        bindDialogAction(dialog,AlertDialog.BUTTON_POSITIVE,origin,() -> {
            getPreferences(MODE_PRIVATE).edit().putBoolean("public_notice",true).apply();
            connect.run();
        });
    }

    private void pakistanDialog(int server,View opener) {
        if (connectionInProgress()) return;
        LinearLayout fields = column();
        fields.setPadding(dp(22),dp(6),dp(22),0);
        EditText username = new EditText(inputContext());
        username.setSingleLine();
        username.setHint("VPN username");
        fields.addView(username);
        EditText password = new EditText(inputContext());
        password.setSingleLine();
        password.setHint("VPN password");
        password.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        fields.addView(password);
        AlertDialog dialog = dialogBuilder().setTitle("Pakistan " + server)
                .setMessage("Use your free VPN Jantit login.")
                .setView(fields).setNegativeButton("Cancel",null)
                .setNeutralButton("Get free login",null)
                .setPositiveButton("Connect",null).create();
        DialogMorph.Origin origin = DialogMorph.origin(opener);
        showAnchoredDialog(dialog,origin,true);
        bindDialogAction(dialog,AlertDialog.BUTTON_NEUTRAL,origin,
                () -> browse(PAKISTAN_ACCOUNT + server + "&type=OpenVPN"));
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String user = username.getText().toString().trim();
            String secret = password.getText().toString();
            if (user.isEmpty() || secret.isEmpty()) {
                toast("Enter your VPN username and password.");
                return;
            }
            closeDialog(dialog,origin,() -> loadProfile("Pakistan " + server,"PK",user,secret,
                    () -> VpnDirectory.pakistanProfile(server),"Couldn’t load the Pakistan profile.",
                    () -> showPakistanImport(server)));
        });
    }

    private void showPakistanImport(int server) {
        if (isFinishing() || !hasWindowFocus()) return;
        showDialog(dialogBuilder().setTitle("Pakistan " + server)
                .setMessage("Download the OpenVPN file from VPN Jantit. In Settings, import the profile and choose Pakistan.")
                .setNegativeButton("Done",null)
                .setPositiveButton("Download",(d,w) -> browse(PAKISTAN_CONFIG + server)).create());
    }

    private interface ProfileLoader { String load() throws Exception; }

    private void loadProfile(String name,String country,String username,String password,ProfileLoader loader,
                             String errorText,Runnable onError) {
        int attempt = begin(name,country);
        profileTask = work.submit(() -> {
            try {
                VpnProfile profile = makeProfile(loader.load(),name,username);
                main.post(() -> {
                    if (active(attempt)) {
                        profileTask = null;
                        prepare(profile,password);
                    }
                });
            } catch (Exception error) {
                main.post(() -> {
                    if (active(attempt)) {
                        attemptFailed(errorText);
                        if (onError != null) onError.run();
                    }
                });
            }
        });
        main.postDelayed(() -> {
            if (active(attempt) && profileTask != null) {
                attemptFailed("This server profile took too long to load.");
                if (onError != null) onError.run();
            }
        },PROFILE_TIMEOUT_MS);
    }

    private boolean connectionInProgress() {
        if (!(connected || busy || started || stopping)) return false;
        toast("Disconnect the current VPN before choosing another server.");
        return true;
    }

    private int begin(String name,String country) {
        displayName = name;
        displayCountry = country;
        message = quick == null ? "" : quickAttemptMessage();
        busy = true;
        stopping = false;
        startedObserved = false;
        engineReplied = false;
        dialStarted = false;
        renderState();
        return ++connectAttempt;
    }

    private String quickAttemptMessage() {
        return "Trying " + displayName + " · attempt " + quick.attempts;
    }

    private boolean active(int attempt) { return !isDestroyed() && busy && connectAttempt == attempt; }

    private VpnProfile makeProfile(String config,String name,String username) throws Exception {
        ConfigParser parser = new ConfigParser();
        parser.parseConfig(new StringReader(config));
        VpnProfile profile = parser.convertProfile();
        profile.mName = name;
        profile.mUsername = username;
        profile.mUseDefaultRoute = true;
        profile.mUseDefaultRoutev6 = true;
        profile.mAllowLocalLAN = false;
        profile.mBlockUnusedAddressFamilies = true;
        int validation = profile.checkProfile(this);
        if (validation != R.string.no_error_found) throw new IllegalArgumentException(getString(validation));
        return profile;
    }

    private String pendingPassword = "";

    private void prepare(VpnProfile profile,String password) {
        pending = profile;
        pendingPassword = password;
        if (quick != null && !foreground) return;
        try {
            Intent permission = VpnService.prepare(this);
            if (permission != null) {
                awaitingPermission = true;
                message = "Waiting for Android VPN permission.";
                renderState();
                startActivityForResult(permission,VPN_PERMISSION);
            } else startTunnel();
        } catch (Exception error) { fail("Android could not start the VPN permission request."); }
    }

    private void startTunnel() {
        VpnProfile profile = pending;
        String password = pendingPassword;
        if (profile == null || !busy) return;
        if (!password.isEmpty() && engineStatus == null) {
            if (!waitingForEngine) {
                waitingForEngine = true;
                int attempt = connectAttempt;
                main.postDelayed(() -> {
                    if (active(attempt) && waitingForEngine) fail("The VPN service took too long to start.");
                },4000);
            }
            return;
        }
        pending = null;
        pendingPassword = "";
        waitingForEngine = false;
        try {
            if (!password.isEmpty()) engineStatus.setCachedPassword(profile.getUUIDString(),PasswordCache.AUTHPASSWORD,password);
            ProfileManager.setTemporaryProfile(this,profile);
            getPreferences(MODE_PRIVATE).edit().putString("last_name",displayName)
                    .putString("last_country",displayCountry).apply();
            activeProfileId = profile.getUUIDString();
            started = true;
            message = quick == null ? "Connecting to " + displayName + "…" : quickAttemptMessage();
            renderState();
            bindController();
            VPNLaunchHelper.startOpenVpn(profile,this,"Setu VPN",false);
            int attempt = connectAttempt;
            if (quick == null) watchConnection(attempt);
            else main.postDelayed(() -> {
                if (active(attempt) && !dialStarted) attemptFailed("The VPN engine took too long to start.");
            },QUICK_STARTUP_TIMEOUT_MS);
        } catch (Exception error) { attemptFailed("Android could not start this VPN connection."); }
    }

    private void watchConnection(int attempt) {
        main.postDelayed(() -> {
            if (!active(attempt) || connected) return;
            if (engineReplied) {
                main.postDelayed(() -> {
                    if (active(attempt) && !connected) attemptFailed("Couldn’t finish connecting. Try another server.");
                },NEGOTIATION_GRACE_MS);
            } else attemptFailed("No response from " + displayName + ". Try another server.");
        },TUNNEL_TIMEOUT_MS);
    }

    private void attemptFailed(String reason) {
        if (quick != null) stop("Trying another server…");
        else if (started || connected) stop(reason);
        else fail(reason);
    }

    private void cancelProfileTask() {
        if (profileTask != null) {
            profileTask.cancel(true);
            profileTask = null;
        }
        waitingForEngine = false;
    }

    private void forceStopService() {
        unbindController();
        stopService(new Intent(this,OpenVPNService.class));
        if (quick == null) clearStopped();
        else {
            int attempt = connectAttempt;
            main.postDelayed(() -> {
                if (stopping && connectAttempt == attempt) clearStopped();
            },QUICK_SHUTDOWN_TIMEOUT_MS);
        }
    }

    private void stop(String reason) {
        connectAttempt++;
        cancelProfileTask();
        pending = null;
        pendingPassword = "";
        awaitingPermission = false;
        message = reason;
        if (!started && !connected) {
            clearStopped();
            return;
        }
        stopping = true;
        busy = false;
        renderState();
        IOpenVPNServiceInternal controller = vpn;
        try {
            if (controller != null && controller.stopVPN(false)) {
                int attempt = connectAttempt;
                main.postDelayed(() -> {
                    if (stopping && connectAttempt == attempt && !isDestroyed()) forceStopService();
                }, quick == null ? 3000 : QUICK_SHUTDOWN_TIMEOUT_MS);
                return;
            }
        } catch (Exception ignored) {
            // The engine may have exited or may still be setting up its controller.
        }
        forceStopService();
    }

    private void clearStopped() {
        connectAttempt++;
        stopping = false;
        busy = quick != null;
        started = false;
        connected = false;
        startedObserved = false;
        activeProfileId = null;
        if (quick != null) {
            message = "Trying another server…";
            main.post(this::advanceQuickConnect);
        } else if ("Disconnected".equals(message)) message = "";
        renderState();
    }

    private void fail(String text) {
        quick = null;
        awaitingPermission = false;
        activeProfileId = null;
        startedObserved = false;
        cancelProfileTask();
        pending = null;
        pendingPassword = "";
        busy = false;
        stopping = false;
        started = false;
        connected = false;
        message = text;
        renderState();
    }

    @Override public void updateState(String raw,String log,int id,ConnectionStatus level,Intent intent) {
        String reportedProfile = VpnStatus.getLastConnectedVPNProfile();
        main.post(() -> {
            if (isDestroyed()) return;
            if (stopping) {
                // EXITING can arrive before OpenVPN's native process releases the service.
                if (level == ConnectionStatus.LEVEL_NOTCONNECTED && (quick == null || "NOPROCESS".equals(raw)))
                    clearStopped();
                return;
            }
            if (quick != null && !started) return;
            boolean currentProfile = activeProfileId != null && activeProfileId.equals(reportedProfile);
            if (level == ConnectionStatus.LEVEL_CONNECTED && activeProfileId != null && !currentProfile) return;
            if (quick != null && level != ConnectionStatus.LEVEL_START && !currentProfile) return;
            int check = ++tunnelCheck;
            switch (level) {
                case LEVEL_CONNECTED:
                    started = true;
                    startedObserved = true;
                    verifyTunnel(check,0);
                    return;
                case LEVEL_START:
                case LEVEL_CONNECTING_SERVER_REPLIED:
                case LEVEL_CONNECTING_NO_SERVER_REPLY_YET:
                case LEVEL_NONETWORK:
                case LEVEL_VPNPAUSED:
                    startedObserved = true;
                    engineReplied |= level == ConnectionStatus.LEVEL_CONNECTING_SERVER_REPLIED;
                    if (quick != null && !dialStarted && (engineReplied
                            || level == ConnectionStatus.LEVEL_CONNECTING_NO_SERVER_REPLY_YET)) {
                        dialStarted = true;
                        watchConnection(connectAttempt);
                    }
                    busy = true;
                    connected = false;
                    started = true;
                    message = level == ConnectionStatus.LEVEL_NONETWORK ? "Waiting for an internet connection."
                            : quick == null ? "" : quickAttemptMessage();
                    break;
                case LEVEL_AUTH_FAILED:
                    if (started || connected) attemptFailed("VPN authentication failed. Check the provider credentials or try another relay.");
                    return;
                case LEVEL_WAITING_FOR_USER_INPUT:
                    if (quick != null) {
                        attemptFailed("The profile requires additional authentication.");
                        return;
                    }
                    startedObserved = true;
                    busy = true;
                    message = "The profile requires additional authentication.";
                    break;
                case LEVEL_NOTCONNECTED:
                    if (quick != null && started) {
                        if ("NOPROCESS".equals(raw)) clearStopped();
                        else attemptFailed("The VPN connection ended.");
                    }
                    else if (startedObserved || connected)
                        fail(connected ? "The VPN connection ended." : "The relay stopped responding. Try another server.");
                    return;
                default:
                    return;
            }
            renderState();
        });
    }

    private void verifyTunnel(int check,int polls) {
        if (check != tunnelCheck || stopping || !started || isDestroyed()) return;
        ConnectivityManager connectivity = getSystemService(ConnectivityManager.class);
        Network network = connectivity == null ? null : connectivity.getActiveNetwork();
        NetworkCapabilities networkCapabilities = network == null ? null : connectivity.getNetworkCapabilities(network);
        boolean activeVpn = networkCapabilities != null
                && networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
                && (Build.VERSION.SDK_INT < 30 || networkCapabilities.getOwnerUid() == android.os.Process.myUid());
        if (activeVpn) {
            if (quick != null && quick.country == null && !displayCountry.isEmpty()) {
                getPreferences(MODE_PRIVATE).edit().putString(LAST_GLOBAL_COUNTRY,displayCountry).apply();
            }
            quick = null;
            connected = true;
            busy = false;
            message = "";
            renderState();
            requestVpnNotification();
            return;
        }
        if (polls == 12) {
            attemptFailed("Android did not activate the VPN tunnel.");
            return;
        }
        connected = false;
        busy = true;
        message = "Checking Android’s VPN connection…";
        renderState();
        main.postDelayed(() -> verifyTunnel(check,polls + 1),250);
    }

    private void requestVpnNotification() {
        if (Build.VERSION.SDK_INT < 33 || !hasWindowFocus()
                || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
                || getPreferences(MODE_PRIVATE).getBoolean("asked_vpn_notification",false)) return;
        getPreferences(MODE_PRIVATE).edit().putBoolean("asked_vpn_notification",true).apply();
        requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},VPN_NOTIFICATION_PERMISSION);
    }

    @Override public void setConnectedVPN(String uuid) {}

    private void renderState() {
        if (state == null) return;
        boolean trying = quick != null;
        transitionText(state,stopping && !trying ? "Disconnecting" : connected && !trying ? "Connected"
                : busy || trying ? "Connecting" : "Disconnected");
        state.setTextColor(ThemeTransition.color(LIGHT.ink,Color.WHITE,colors.amount()));
        transitionText(detail,!message.isEmpty() ? message : (connected || busy) && !displayName.isEmpty() ? displayName
                : "Choose a location or connect to a relay.");
        transitionText(action,trying ? "Cancel" : stopping ? "Disconnecting…" : connected ? "Disconnect"
                : busy ? "Cancel" : "Quick Connect");
        action.setEnabled(!stopping || trying);
        countryActions.forEach(this::renderCountryAction);
        animateConnectionMeter();
    }

    private void renderCountryAction(String code,TextView toggle) {
        boolean active = ownsCountryAction(code);
        String label = !active ? "Quick Connect" : quick != null ? "Cancel" : stopping ? "Disconnecting…"
                : connected ? "Disconnect" : "Cancel";
        transitionText(toggle,label);
        toggle.setContentDescription(label + " for " + VpnDirectory.name(code));
        toggle.setEnabled(!active || !stopping || quick != null);
    }

    private static final class Imported {
        final String name, country, config;
        final File file;
        Imported(String n,String c,String p,File f) {
            name = n; country = c; config = p; file = f;
        }

        boolean needsLogin() {
            return config.contains("\nauth-user-pass\n") || config.startsWith("auth-user-pass\n");
        }
    }

    private File importsDir() { return new File(getFilesDir(),"paperline-profiles"); }

    private List<Imported> loadImports() {
        List<Imported> list = new ArrayList<>();
        File[] files = importsDir().listFiles((dir,name) -> name.endsWith(".json"));
        if (files == null) return list;
        for (File file : files) {
            try (InputStream stream = new AtomicFile(file).openRead()) {
                JSONObject data = new JSONObject(VpnDirectory.utf8(VpnDirectory.read(stream,200_000)));
                list.add(new Imported(data.getString("name"),data.getString("country"),data.getString("config"),file));
            } catch (Exception ignored) {
                // Skip damaged profiles so other imports remain usable.
            }
        }
        list.sort(Comparator.comparing(x -> x.name.toLowerCase(Locale.ROOT)));
        return list;
    }

    private void pickProfile(String country) {
        importCountry = country;
        Intent picker = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*");
        startActivityForResult(picker,PICK_PROFILE);
    }

    @Override protected void onActivityResult(int request,int result,Intent data) {
        super.onActivityResult(request,result,data);
        if (request == VPN_PERMISSION && awaitingPermission) {
            awaitingPermission = false;
            if (result == RESULT_OK) startTunnel();
            else fail("VPN permission was declined.");
        }
        if (request == PICK_PROFILE && result == RESULT_OK && data != null && data.getData() != null) {
            importFile(data.getData(),importCountry);
        }
    }

    private void importFile(Uri uri,String country) {
        work.execute(() -> {
            List<Imported> saved = new ArrayList<>();
            try {
                String name = "Imported profile";
                try (android.database.Cursor c = getContentResolver().query(uri,new String[]{OpenableColumns.DISPLAY_NAME},null,null,null)) {
                    if (c != null && c.moveToFirst() && !c.isNull(0)) name = c.getString(0);
                }
                List<OpenVpnFiles.Profile> profiles;
                try (InputStream in = getContentResolver().openInputStream(uri)) {
                    profiles = OpenVpnFiles.read(in,name);
                }
                for (OpenVpnFiles.Profile profile : profiles) makeProfile(profile.config,profile.name,"");
                for (OpenVpnFiles.Profile profile : profiles) saved.add(saveImport(profile,country));
                main.post(() -> {
                    if (isDestroyed()) return;
                    imported.addAll(saved);
                    expanded.add(country);
                    renderLocations();
                    reveal(locations,7,270,0.7f,0);
                    toast(saved.size() == 1 ? "OpenVPN profile imported." : saved.size() + " OpenVPN profiles imported.");
                });
            } catch (Exception error) {
                for (Imported profile : saved) new AtomicFile(profile.file).delete();
                main.post(() -> {
                    if (isDestroyed()) return;
                    String why = error.getMessage();
                    showDialog(dialogBuilder().setTitle("Couldn’t import profile")
                            .setMessage(why != null && why.length() < 160 ? why : "Choose an OpenVPN .ovpn file or a ZIP of profiles.")
                            .setPositiveButton("Done",null).create());
                });
            }
        });
    }

    private Imported saveImport(OpenVpnFiles.Profile profile,String country) throws Exception {
        File folder = importsDir();
        if (!folder.exists() && !folder.mkdirs() && !folder.isDirectory())
            throw new IOException("Could not save the profile");
        File file = new File(folder,UUID.randomUUID() + ".json");
        AtomicFile atomic = new AtomicFile(file);
        byte[] bytes = VpnDirectory.utf8(new JSONObject().put("name",profile.name)
                .put("country",country).put("config",profile.config).toString());
        FileOutputStream out = atomic.startWrite();
        try {
            out.write(bytes);
            atomic.finishWrite(out);
        } catch (Exception error) {
            atomic.failWrite(out);
            throw error;
        }
        return new Imported(profile.name,country,profile.config,file);
    }

    private void connectImport(Imported profile,View opener) {
        if (connectionInProgress()) return;
        if (!profile.needsLogin()) {
            connectImport(profile,"","");
            return;
        }
        LinearLayout fields = column();
        fields.setPadding(dp(22),dp(8),dp(22),0);
        EditText user = new EditText(inputContext());
        user.setSingleLine();
        user.setHint("VPN username");
        fields.addView(user);
        EditText password = new EditText(inputContext());
        password.setSingleLine();
        password.setHint("VPN password");
        password.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        fields.addView(password);
        AlertDialog dialog = dialogBuilder().setTitle("Provider login").setView(fields)
                .setNegativeButton("Cancel",null).setPositiveButton("Connect",null).create();
        DialogMorph.Origin origin = DialogMorph.origin(opener);
        showAnchoredDialog(dialog,origin,true);
        bindDialogAction(dialog,AlertDialog.BUTTON_POSITIVE,origin,
                () -> connectImport(profile,user.getText().toString(),password.getText().toString()));
    }

    private void connectImport(Imported profile,String username,String password) {
        loadProfile(profile.name,profile.country,username,password,() -> profile.config,
                "Couldn’t start the imported profile.",null);
    }

    private void deleteImport(Imported profile,View opener) {
        AlertDialog dialog = dialogBuilder().setTitle("Remove " + profile.name + "?")
                .setNegativeButton("Cancel",null).setPositiveButton("Remove",null).create();
        DialogMorph.Origin origin = DialogMorph.origin(opener);
        showAnchoredDialog(dialog,origin,true);
        bindDialogAction(dialog,AlertDialog.BUTTON_POSITIVE,origin,() -> {
            new AtomicFile(profile.file).delete();
            imported.remove(profile);
            renderLocations();
            reveal(locations,-4,230,0.75f,0);
        });
    }

    private void browse(String link) {
        try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(link))); }
        catch (Exception absent) { toast("A browser is required to open the provider page."); }
    }

    private void hideKeyboard() {
        InputMethodManager manager = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (manager != null && search != null) manager.hideSoftInputFromWindow(search.getWindowToken(),0);
        if (search != null) search.clearFocus();
    }

    private void toast(String t) { Toast.makeText(this,t,Toast.LENGTH_SHORT).show(); }
    private int dp(float v) { return Math.round(v * getResources().getDisplayMetrics().density); }
    private LinearLayout column() {
        LinearLayout view = new LinearLayout(this);
        view.setOrientation(LinearLayout.VERTICAL);
        return view;
    }
    private LinearLayout row() {
        LinearLayout view = new LinearLayout(this);
        view.setOrientation(LinearLayout.HORIZONTAL);
        view.setGravity(Gravity.CENTER_VERTICAL);
        return view;
    }
    private LinearLayout.LayoutParams weighted() { return new LinearLayout.LayoutParams(0,-2,1); }

    private TextView text(String s,int size,int color,String family,int style) {
        return text(s,size,color,family,style,true);
    }

    private TextView text(String s,int size,int color,String family,int style,boolean themed) {
        TextView text = new TextView(this);
        text.setText(s);
        if (themed) colorText(text,color);
        else text.setTextColor(color);
        text.setTextSize(size);
        text.setTypeface(Typeface.create(family,style));
        return text;
    }

    private void animateConnectionMeter() {
        boolean active = connected && !stopping;
        boolean working = !active && (busy || stopping || quick != null);
        boolean animate = ValueAnimator.areAnimatorsEnabled();
        boolean moving = foreground && working && animate;
        boolean changed = meter.active != active;
        meter.mode(working,active);
        if (moving && connectionMotion == null) {
            connectionMotion = ValueAnimator.ofFloat(0f,1f);
            connectionMotion.setDuration(1500);
            connectionMotion.setInterpolator(new LinearInterpolator());
            connectionMotion.setRepeatCount(ValueAnimator.INFINITE);
            connectionMotion.addUpdateListener(animation -> meter.setPhase((float) animation.getAnimatedValue()));
            connectionMotion.start();
        } else if (!moving && connectionMotion != null) {
            connectionMotion.cancel();
            connectionMotion = null;
            meter.setPhase(0f);
        }
        if (changed && animate) reveal(meter,0,260,0.5f,0);
    }

    private void reveal(View view,int distance,int duration,float startingAlpha,int delay) {
        if (!ValueAnimator.areAnimatorsEnabled()) return;
        view.animate().cancel();
        view.setAlpha(startingAlpha);
        view.setTranslationY(dp(distance));
        view.animate().alpha(1f).translationY(0).setDuration(duration).setStartDelay(delay)
                .setInterpolator(SPRING).start();
    }

    private Context inputContext() {
        return new ContextThemeWrapper(this,theme.dark ? android.R.style.Theme_Material : android.R.style.Theme_Material_Light);
    }

    private AlertDialog.Builder dialogBuilder() {
        return new AlertDialog.Builder(this,theme.dark ? android.R.style.Theme_Material_Dialog_Alert
                : android.R.style.Theme_Material_Light_Dialog_Alert);
    }

    private void showDialog(AlertDialog dialog) {
        showDialog(dialog,null,true);
    }

    private void showAnchoredDialog(AlertDialog dialog,DialogMorph.Origin origin,boolean animateOpening) {
        showDialog(dialog,origin,animateOpening);
        bindDialogAction(dialog,AlertDialog.BUTTON_POSITIVE,origin,null);
        bindDialogAction(dialog,AlertDialog.BUTTON_NEGATIVE,origin,null);
        bindDialogAction(dialog,AlertDialog.BUTTON_NEUTRAL,origin,null);
    }

    private void bindDialogAction(AlertDialog dialog,int role,DialogMorph.Origin origin,Runnable after) {
        Button actionButton = dialog.getButton(role);
        if (actionButton != null) actionButton.setOnClickListener(view -> closeDialog(dialog,origin,after));
    }

    private void bindDialogBack(AlertDialog dialog,Runnable back) {
        if (Build.VERSION.SDK_INT >= 33) {
            dialog.getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT,back::run);
        }
        dialog.setOnKeyListener((owner,key,event) -> {
            if (key != KeyEvent.KEYCODE_BACK) return false;
            if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) back.run();
            return true;
        });
    }

    private void closeDialog(AlertDialog dialog,DialogMorph.Origin origin,Runnable after) {
        dialogMorph.close(dialog,origin,after);
    }

    private void showDialog(AlertDialog dialog,DialogMorph.Origin origin,boolean animateOpening) {
        if (dialogMorph.show(dialog,origin,animateOpening,theme.dialog,theme.divider,
                () -> styleDialogActions(dialog))) return;
        if ((!animateOpening || !ValueAnimator.areAnimatorsEnabled()) && dialog.getWindow() != null)
            dialog.getWindow().setWindowAnimations(0);
        presentDialog(dialog);
        Window window = dialog.getWindow();
        if (!animateOpening || !ValueAnimator.areAnimatorsEnabled() || window == null) return;
        View content = window.getDecorView();
        content.setAlpha(0.5f);
        content.setScaleX(0.94f);
        content.setScaleY(0.94f);
        content.setTranslationY(dp(14));
        content.animate().alpha(1f).scaleX(1f).scaleY(1f).translationY(0)
                .setInterpolator(SPRING).setDuration(385).start();
    }

    private void presentDialog(AlertDialog dialog) {
        dialog.show();
        styleDialogActions(dialog);
        styleDialogWindow(dialog.getWindow());
    }

    private void styleDialogWindow(Window window) {
        if (window == null) return;
        android.graphics.drawable.GradientDrawable face = new android.graphics.drawable.GradientDrawable();
        face.setColor(theme.dialog);
        face.setCornerRadius(dp(28));
        face.setStroke(dp(1),theme.divider);
        window.setBackgroundDrawable(new InsetDrawable(face,dp(12)));
        colors.bind(window.getDecorView(),night -> {
            face.setColor(ThemeTransition.color(LIGHT.dialog,DARK.dialog,night));
            face.setStroke(dp(1),ThemeTransition.color(LIGHT.divider,DARK.divider,night));
        });
    }

    private void styleDialogActions(AlertDialog dialog) {
        int[] roles = {AlertDialog.BUTTON_POSITIVE, AlertDialog.BUTTON_NEGATIVE, AlertDialog.BUTTON_NEUTRAL};
        for (int role : roles) {
            Button actionButton = dialog.getButton(role);
            if (actionButton == null) continue;
            actionButton.setAllCaps(false);
            actionButton.setLetterSpacing(0f);
            actionButton.setTypeface(actionTypeface());
            actionButton.setTextColor(Color.WHITE);
            actionButton.setStateListAnimator(null);
            actionButton.setElevation(0f);
            actionButton.setTranslationZ(0f);
            android.graphics.drawable.GradientDrawable fill = new android.graphics.drawable.GradientDrawable();
            fill.setCornerRadius(dp(18));
            InsetDrawable background = new InsetDrawable(fill,dp(2),dp(4),dp(2),dp(4));
            colors.background(actionButton,background,night ->
                    fill.setColor(ThemeTransition.color(0xff272a31,0x00272a31,night)));
            actionButton.setPadding(dp(12),dp(8),dp(12),dp(8));
        }
    }

    private Typeface actionTypeface() {
        if (actionTypeface == null) {
            try {
                actionTypeface = new Typeface.Builder(getAssets(),"fonts/Inter.ttf")
                        .setFontVariationSettings("'wght' 600").build();
            } catch (RuntimeException error) {
                android.util.Log.w("Setu VPN","Could not load Inter",error);
            }
            if (actionTypeface == null) actionTypeface = Typeface.create("sans-serif-medium",Typeface.NORMAL);
        }
        return actionTypeface;
    }

    private void transitionText(TextView view,String text) {
        if (text.contentEquals(view.getText())) return;
        view.setText(text);
        if (foreground) reveal(view,3,195,0.58f,0);
    }

    private TextView button(String value,boolean solid) {
        TextView view = actionLabel(value,14,solid ? theme.primaryText : theme.ink);
        view.setPadding(dp(10),0,dp(10),0);
        view.setMinimumHeight(dp(42));
        colorGlass(view,solid ? GlassTheme.Surface.PRIMARY : GlassTheme.Surface.CONTROL,27);
        return view;
    }

    private TextView actionLabel(String value,int size,int color) {
        TextView view = text(value,size,color,"sans-serif-medium",Typeface.NORMAL);
        view.setTypeface(actionTypeface());
        view.setGravity(Gravity.CENTER);
        view.setFocusable(true);
        view.setAccessibilityDelegate(new View.AccessibilityDelegate() {
            @Override public void onInitializeAccessibilityNodeInfo(View host,android.view.accessibility.AccessibilityNodeInfo info) {
                super.onInitializeAccessibilityNodeInfo(host,info);
                info.setClassName("android.widget.Button");
            }
        });
        return view;
    }

    private void paragraph(LinearLayout parent,String copy) {
        TextView text = text(copy,13,theme.muted,"sans-serif",Typeface.NORMAL);
        text.setPadding(dp(3),dp(12),dp(3),dp(12));
        parent.addView(text,new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,-2));
    }

    private void rule(LinearLayout view,int color,int height,int margin) {
        View line = new View(this);
        ColorDrawable fill = new ColorDrawable(color);
        colors.background(line,fill,night -> fill.setColor(ThemeTransition.color(LIGHT.divider,DARK.divider,night)));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1,dp(height));
        params.topMargin = dp(margin);
        view.addView(line,params);
    }

    private void gap(LinearLayout view,int h) { view.addView(new View(this),new LinearLayout.LayoutParams(1,dp(h))); }

    private float rim() { return getResources().getDisplayMetrics().density * .8f; }

    private int connectedColor() {
        return ThemeTransition.color(LIGHT.connected,DARK.connected,colors.amount());
    }

    private void colorText(TextView view,int color) {
        if (color == theme.ink) colorText(view,LIGHT.ink,DARK.ink);
        else if (color == theme.muted) colorText(view,LIGHT.muted,DARK.muted);
        else if (color == theme.accent) colorText(view,LIGHT.accent,DARK.accent);
        else if (color == theme.primaryText) colorText(view,LIGHT.primaryText,DARK.primaryText);
        else view.setTextColor(color);
    }

    private void colorText(TextView view,int light,int dark) {
        colors.bind(view,night -> view.setTextColor(ThemeTransition.color(light,dark,night)));
    }

    private ThemeTransition.Blended blendGlass(GlassTheme.Surface surface,int radius) {
        return new ThemeTransition.Blended(LIGHT.surface(surface,dp(radius),rim()),
                DARK.surface(surface,dp(radius),rim()),false);
    }

    private ThemeTransition.Blended colorGlass(View view,GlassTheme.Surface surface,int radius) {
        ThemeTransition.Blended drawable = blendGlass(surface,radius);
        colors.background(view,drawable,drawable::setNight);
        return drawable;
    }

    private final class LabelText extends TextView {
        LabelText(String label,int size,int color) {
            super(PaperlineActivity.this);
            setText(label);
            setTextSize(size);
            setLetterSpacing(.14f);
            colorText(this,color);
            setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));
        }
    }

    private final class CountryPanel extends LinearLayout {
        private final String country;
        private ValueAnimator slide;
        private TextView quickButton;

        CountryPanel(String country) {
            super(PaperlineActivity.this);
            this.country = country;
            setOrientation(VERTICAL);
            setPadding(dp(11),dp(4),dp(11),dp(12));
        }

        void slide(boolean open) {
            cancelSlide();
            LinearLayout parent = (LinearLayout) getParent();
            if (parent == null) return;
            setImportantForAccessibility(open ? IMPORTANT_FOR_ACCESSIBILITY_AUTO : IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
            ViewGroup.LayoutParams params = getLayoutParams();
            if (!ValueAnimator.areAnimatorsEnabled() || parent.getWidth() == 0) {
                settle(open,parent);
                return;
            }
            int from = params.height < 0 ? getHeight() : params.height;
            measure(MeasureSpec.makeMeasureSpec(parent.getWidth(),MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(0,MeasureSpec.UNSPECIFIED));
            int to = open ? getMeasuredHeight() : 0;
            if (from == to) {
                settle(open,parent);
                return;
            }
            float initialAlpha = from == 0 ? 0f : getAlpha();
            slide = ValueAnimator.ofInt(from,to);
            slide.setDuration(open ? 390 : 240);
            slide.setInterpolator(open ? SPRING : EASE);
            slide.addUpdateListener(animation -> {
                params.height = (int) animation.getAnimatedValue();
                setLayoutParams(params);
                float fraction = Math.min(1f,animation.getAnimatedFraction());
                setAlpha(initialAlpha + ((open ? 1f : 0f) - initialAlpha) * fraction);
            });
            slide.addListener(new AnimatorListenerAdapter() {
                @Override public void onAnimationEnd(Animator animation) {
                    if (slide != animation) return;
                    slide = null;
                    settle(open,parent);
                }
            });
            slide.start();
        }

        private void settle(boolean open,LinearLayout parent) {
            if (!open) {
                parent.removeView(this);
                return;
            }
            ViewGroup.LayoutParams params = getLayoutParams();
            params.height = ViewGroup.LayoutParams.WRAP_CONTENT;
            setLayoutParams(params);
            setAlpha(1f);
        }

        private void cancelSlide() {
            ValueAnimator previous = slide;
            slide = null;
            if (previous != null) previous.cancel();
        }

        @Override protected void onDetachedFromWindow() {
            if (quickButton != null) countryActions.remove(country,quickButton);
            cancelSlide();
            super.onDetachedFromWindow();
        }
    }

    private final class ConnectionMeter extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private boolean working, active;
        private float phase;

        ConnectionMeter() {
            super(PaperlineActivity.this);
            setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        }

        void mode(boolean working,boolean active) {
            if (working == this.working && active == this.active) return;
            this.working = working;
            this.active = active;
            invalidate();
        }

        void setPhase(float phase) {
            this.phase = phase;
            invalidate();
        }

        @Override protected void onDraw(Canvas canvas) {
            float width = getWidth();
            float height = getHeight();
            float radius = height / 2f;
            paint.setColor(active ? connectedColor() : ThemeTransition.color(LIGHT.meterTrack,DARK.meterTrack,colors.amount()));
            canvas.drawRoundRect(0,0,width,height,radius,radius,paint);
            if (!working) return;
            float segment = width * 0.30f;
            float start = (width + segment) * phase - segment;
            if (!ValueAnimator.areAnimatorsEnabled()) start = 0;
            paint.setColor(connectedColor());
            canvas.drawRoundRect(Math.max(0,start),0,Math.min(width,start+segment),height,radius,radius,paint);
        }
    }
}
