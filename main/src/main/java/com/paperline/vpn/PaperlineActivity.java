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
import android.content.pm.ResolveInfo;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.BitmapDrawable;
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
import android.os.SystemClock;
import android.provider.OpenableColumns;
import android.provider.Settings;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.util.AtomicFile;
import android.util.LruCache;
import android.util.SparseArray;
import android.view.ContextThemeWrapper;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
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
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
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
import java.util.Objects;
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
    private static final int HEADER_HEIGHT = 62;
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
    private final Map<String,TextView> lastServerCaptions = new HashMap<>();
    private final List<Imported> imported = new ArrayList<>();
    private final LruCache<String, Bitmap> countryIcons = new LruCache<>(96);
    private Typeface actionTypeface;
    private VpnDirectory directory;
    private ConnectionHistory history;
    private VpnOptions options;
    private ConnectionHistory.Entry endpoint;
    private String attemptNetwork = "other", resumeConfig;
    private long connectedAt, wireRequest;
    private Imported pendingWireGuard;
    private boolean wireGuard, supervisedReconnect, restoring;
    private boolean successfulAttempt, attemptRecorded;
    private int reconnectCount;
    private String lastError = "";
    private Recovery recovery;
    private final Runnable wireChanges = this::onWireGuardChanged;
    private final Runnable reconnect = this::resumeOpenVpn;
    private final Runnable sessionTick = this::tickSession;
    private final Runnable switchCheck = this::finishSwitch;
    private PendingSwitch pendingSwitch;
    private VpnDirectory.Edition edition = new VpnDirectory.Edition();
    private LinearLayout locations;
    private GlassTheme theme;
    private GlassRefreshScroll scroll;
    private TextView state, detail, action;
    private TextView sessionClock, disconnectButton, recoveryButton;
    private LinearLayout sessionActions;
    private Map<String,ConnectionHistory.Entry> lastByCountry = Collections.emptyMap();
    private boolean showingSwitchActions;
    private String markedEndpoint;
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
            else if (started && quick != null) {
                lastError = "The OpenVPN service stopped.";
                message = "Trying another server… " + lastError;
                recordFailure();
                clearStopped();
            }
            else if (started && !wireGuard) connectionLost("The OpenVPN service stopped.");
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
        history = new ConnectionHistory(this);
        lastByCountry = history.lastByCountry();
        options = new VpnOptions(this);
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
        SetuVpnService.listen(wireChanges);
        onWireGuardChanged();
        VpnStatus.addStateListener(this);
        bindController();
        statusBound = bindService(new Intent(this,OpenVPNStatusService.class), statusConnection, Context.BIND_AUTO_CREATE);
        advanceQuickConnect();
        if (supervisedReconnect && !started && !stopping && profileTask == null) scheduleReconnect();
        if (pendingSwitch != null && !stopping) finishSwitch();
        syncSessionClock();
        animateConnectionMeter();
    }

    private void bindController() {
        if (bound) return;
        Intent request = new Intent(this,OpenVPNService.class).setAction(OpenVPNService.START_SERVICE);
        bound = bindService(request,vpnConnection,Context.BIND_AUTO_CREATE);
    }

    @Override protected void onStop() {
        foreground = false;
        SetuVpnService.unlisten(wireChanges);
        main.removeCallbacks(reconnect);
        main.removeCallbacks(sessionTick);
        main.removeCallbacks(switchCheck);
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
        sheet.setPadding(dp(19),dp(6),dp(19),dp(16));
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
        root.addView(mast,new FrameLayout.LayoutParams(-1,dp(HEADER_HEIGHT),Gravity.TOP));

        LinearLayout card = column();
        card.setPadding(dp(18),dp(16),dp(18),dp(15));
        colorGlass(card,GlassTheme.Surface.PANEL,30);
        colors.bind(card,night -> card.setElevation(dp(3) * (1f - night)));

        LinearLayout words = column();
        words.setGravity(Gravity.CENTER);
        words.setMinimumHeight(dp(64));
        state = text("Disconnected",29,Color.WHITE,"sans-serif-medium",Typeface.NORMAL, false);
        colorText(state,LIGHT.ink,Color.WHITE);
        state.setGravity(Gravity.CENTER);
        state.setLetterSpacing(-0.045f);
        words.addView(state);
        detail = text("",13,theme.muted,"sans-serif",Typeface.NORMAL);
        detail.setPadding(0,dp(4),0,0);
        detail.setGravity(Gravity.CENTER);
        words.addView(detail);
        card.addView(words,new LinearLayout.LayoutParams(-1,-2));
        words.setOnClickListener(this::showConnectionDetails);
        words.setFocusable(true);
        sessionActions = row();
        sessionActions.setGravity(Gravity.CENTER);
        sessionClock = text("",13,theme.muted,"sans-serif",Typeface.NORMAL);
        sessionClock.setTypeface(Typeface.MONOSPACE);
        sessionClock.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        sessionActions.addView(sessionClock);
        disconnectButton = actionLabel("Disconnect",13,theme.accent);
        disconnectButton.setPadding(dp(12),0,dp(12),0);
        disconnectButton.setMinimumHeight(dp(48));
        disconnectButton.setOnClickListener(this::endConnection);
        sessionActions.addView(disconnectButton);
        card.addView(sessionActions,new LinearLayout.LayoutParams(-1,-2));
        recoveryButton = actionLabel("",14,theme.accent);
        recoveryButton.setMinimumHeight(dp(48));
        recoveryButton.setOnClickListener(this::recoverConnection);
        card.addView(recoveryButton);

        meter = new ConnectionMeter();
        colors.bind(meter,night -> meter.invalidate());
        LinearLayout.LayoutParams meterLayout = new LinearLayout.LayoutParams(dp(56),dp(3));
        meterLayout.gravity = Gravity.CENTER_HORIZONTAL;
        meterLayout.topMargin = dp(9);
        card.addView(meter,meterLayout);
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
        addWindowInsets(root,viewport,mast,dock);

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

    private void addWindowInsets(FrameLayout root,GlassRefreshScroll viewport,View mast,FrameLayout dock) {
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
            int headerBottom = top + dp(HEADER_HEIGHT);
            if (scrollLayout.bottomMargin != reserved || scrollLayout.topMargin != headerBottom) {
                scrollLayout.bottomMargin = reserved;
                scrollLayout.topMargin = headerBottom;
                viewport.setLayoutParams(scrollLayout);
            }
            viewport.setPadding(left,0,right,typing ? keyboardBottom : 0);
            FrameLayout.LayoutParams headerLayout = (FrameLayout.LayoutParams) mast.getLayoutParams();
            if (headerLayout.topMargin != top || headerLayout.leftMargin != left + dp(19)
                    || headerLayout.rightMargin != right + dp(19)) {
                headerLayout.setMargins(left + dp(19),top,right + dp(19),0);
                mast.setLayoutParams(headerLayout);
            }
            View indicator = viewport.indicator();
            FrameLayout.LayoutParams indicatorLayout = (FrameLayout.LayoutParams) indicator.getLayoutParams();
            if (indicatorLayout.topMargin != headerBottom + dp(6) || indicatorLayout.leftMargin != left
                    || indicatorLayout.rightMargin != right) {
                indicatorLayout.setMargins(left,headerBottom + dp(6),right,0);
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
                    if (edition.best.isEmpty()) {
                        recovery = Recovery.REFRESH;
                        fail("No servers are available right now.");
                    } else advanceQuickConnect();
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
        lastServerCaptions.clear();
        String term = LocationSearch.normalize(search.getText().toString().trim());
        List<String> codes = new ArrayList<>(locationCodes(allCountries || !term.isEmpty()));
        Map<String,Integer> matches = new HashMap<>();
        for (String code : codes) matches.put(code,LocationSearch.match(code,VpnDirectory.name(code),term));
        codes.removeIf(code -> matches.get(code) == 0);
        Set<String> readyImports = new LinkedHashSet<>();
        for (Imported profile : imported) if (!profile.needsLogin()) readyImports.add(profile.country);
        codes.sort(Comparator.comparingInt((String code) -> listedRelays(code).isEmpty()
                        && !readyImports.contains(code) ? 1 : 0)
                .thenComparing(Comparator.comparingInt((String code) -> matches.get(code)).reversed())
                .thenComparing(Comparator.comparingInt((String code) -> listedRelays(code).size()).reversed()));
        int shown = 0;
        for (String code : codes) {
            String name = VpnDirectory.name(code);
            List<VpnDirectory.Relay> entries = listedRelays(code);
            List<Imported> local = importsFor(code);
            if (allCountries && term.isEmpty() && entries.isEmpty() && local.isEmpty()) continue;
            countryRow(code,name,entries,local);
            shown++;
        }
        List<Imported> uncategorized = importsFor("");
        if (!term.isEmpty()) uncategorized.removeIf(profile -> !LocationSearch.normalize(profile.name).contains(term));
        if (!uncategorized.isEmpty()) {
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
            List<String> availability = new ArrayList<>(3);
            if (!entries.isEmpty()) availability.add(entries.size() + (entries.size() == 1 ? " listed relay" : " listed relays"));
            long ready = local.stream().filter(profile -> !profile.needsLogin()).count();
            if (ready > 0) availability.add(ready + (ready == 1 ? " imported profile" : " imported profiles"));
            if (local.size() > ready) availability.add("Login required");
            caption = String.join(" · ",availability);
        }
        labels.addView(text(caption,12,theme.muted,"sans-serif",Typeface.NORMAL));
        TextView lastServer = text("",12,theme.muted,"sans-serif",Typeface.NORMAL);
        lastServer.setPadding(0,dp(3),0,0);
        labels.addView(lastServer);
        lastServerCaptions.put(code,lastServer);
        showLastServer(code,lastServer);
        heading.addView(labels,weighted());
        TextView arrow = text("›",25,theme.muted,"sans-serif",Typeface.NORMAL);
        arrow.setGravity(Gravity.CENTER);
        arrow.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        heading.addView(arrow,new LinearLayout.LayoutParams(dp(27),dp(35)));
        heading.setFocusable(true);
        boolean isOpen = expanded.contains(code);
        updateCountryHeading(heading,arrow,name,isOpen,false);
        heading.setOnClickListener(view -> {
            hideKeyboard();
            boolean opening = expanded.add(code);
            boolean fullList = !opening && showingAllRelays.remove(code);
            if (!opening) expanded.remove(code);
            updateCountryHeading(heading,arrow,name,opening,true);
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

    private void updateCountryHeading(View heading,View arrow,String name,boolean open,boolean animate) {
        if (Build.VERSION.SDK_INT >= 30) heading.setStateDescription(open ? "Expanded" : "Collapsed");
        else heading.setContentDescription(name + (open ? ". Collapse." : ". Expand."));
        float angle = open ? 90f : 0f;
        arrow.animate().cancel();
        if (animate && ValueAnimator.areAnimatorsEnabled()) {
            arrow.animate().rotation(angle).setInterpolator(SPRING).setDuration(310).start();
        } else arrow.setRotation(angle);
    }

    private void showLastServer(String code,TextView label) {
        ConnectionHistory.Entry last = lastByCountry.get(code);
        label.setVisibility(last == null ? View.GONE : View.VISIBLE);
        if (last != null) label.setText("Last connected · " + last.name);
    }

    private void updateLastServers() {
        lastByCountry = history.lastByCountry();
        lastServerCaptions.forEach(this::showLastServer);
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
        boolean canQuickConnect = !entries.isEmpty() || local.stream().anyMatch(profile -> !profile.needsLogin() && !profile.wireGuard);
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
            ServerTile tile = (ServerTile)addTile(children,profile.name,"Imported " + profile.protocol() + " profile","Connect",
                    view -> connectImport(profile,view));
            tile.favorite(profile.entry());
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
                ServerTile tile = (ServerTile)addTile(children,label,relay.caption(),"Connect",view -> connectRelay(relay,label,view));
                tile.favorite(ConnectionHistory.Entry.relay(relay,label));
            }
            if (entries.size() > limit) {
                TextView more = button(String.format(Locale.getDefault(),"Show all %,d servers",entries.size()),false);
                more.setOnClickListener(view -> {
                    showingAllRelays.add(code);
                    children.cancelSlide();
                    children.removeAllViews();
                    populateCountryPanel(children,code,name,entries,local,heading);
                    children.settle(true,(LinearLayout)children.getParent());
                    reveal(children,5,205,0.89f,0);
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
        private final TextView title, subtitle, action, star;
        private final boolean connectionAction;
        private ConnectionHistory.Entry target;

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
            star = actionLabel("☆",22,theme.accent);
            star.setGravity(Gravity.CENTER);
            star.setVisibility(View.GONE);
            addView(star,new LinearLayout.LayoutParams(dp(43),dp(46)));
            connectionAction = "Connect".equals(verb);
            action = button(verb,false);
            action.setMinimumWidth(dp(75));
            action.setMinimumHeight(dp(48));
            addView(action);
        }

        void favorite(ConnectionHistory.Entry entry) {
            target = entry;
            star.setVisibility(View.VISIBLE);
            updateStar(entry,history.favorite(entry.id));
            star.setOnClickListener(view -> updateStar(entry,history.toggleFavorite(entry)));
            refreshVerb();
        }

        private void updateStar(ConnectionHistory.Entry entry,boolean saved) {
            star.setText(saved ? "★" : "☆");
            star.setContentDescription((saved ? "Remove favorite " : "Favorite ") + entry.name);
        }

        void bind(String name,String caption,Consumer<View> click) {
            title.setText(name);
            subtitle.setText(caption);
            action.setOnClickListener(click::accept);
        }

        void refreshVerb() {
            if (!connectionAction) return;
            boolean current = target != null && isCurrent(target);
            action.setText(current ? "Connected" : connected && !stopping && pendingSwitch == null ? "Switch" : "Connect");
            action.setEnabled(!current);
            action.setContentDescription(action.getText() + " · " + title.getText());
        }
    }

    private void refreshServerActions(View parent) {
        if (parent instanceof ServerTile) ((ServerTile)parent).refreshVerb();
        else if (parent instanceof ViewGroup) {
            ViewGroup group = (ViewGroup)parent;
            for (int i = 0;i < group.getChildCount();i++) refreshServerActions(group.getChildAt(i));
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
                    tile.favorite(ConnectionHistory.Entry.relay(relay,label));
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
        rule(preferences,theme.divider,1,0);
        Switch recovery = settingsSwitch(preferences,"Auto-reconnect",options.autoReconnect());
        recovery.setOnCheckedChangeListener((button,checked) -> {
            options.setAutoReconnect(checked);
            if (!checked && supervisedReconnect) {
                cancelReconnect();
                stop("Auto-reconnect canceled.");
            }
        });
        rule(preferences,theme.divider,1,0);
        settingsAction(preferences,"Split tunneling",this::showSplitTunneling);
        rule(preferences,theme.divider,1,0);
        settingsAction(preferences,"Kill switch",this::showKillSwitch);

        gap(content,10);
        LinearLayout actions = settingsCard(content);
        settingsAction(actions,"Favorites",view -> showSaved(menu,true,view));
        rule(actions,theme.divider,1,0);
        settingsAction(actions,"Recent connections",view -> showSaved(menu,false,view));
        rule(actions,theme.divider,1,0);
        settingsAction(actions,"Import OpenVPN or WireGuard profile",view -> {
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

    private static final class PendingSwitch {
        final String destination;
        final Runnable connect;
        final boolean previousWireGuard;
        final long previousConnectedAt;
        long deadline;

        PendingSwitch(String destination,Runnable connect,boolean previousWireGuard,long previousConnectedAt) {
            this.destination = destination;
            this.connect = connect;
            this.previousWireGuard = previousWireGuard;
            this.previousConnectedAt = previousConnectedAt;
        }
    }

    private void heroAction(View view) {
        if (pendingSwitch == null && connected && !stopping) {
            showSwitchLocation(view);
            return;
        }
        if (quick != null || busy || started || stopping || pendingSwitch != null) {
            endConnection(view);
            return;
        }
        List<Imported> ready = new ArrayList<>(imported);
        ready.removeIf(profile -> profile.needsLogin() || profile.wireGuard);
        QuickSession session = new QuickSession(null,ready);
        Runnable launch = () -> chooseConnection(view,"Quick Connect",() -> startQuickConnect(session));
        if (edition.best.isEmpty() && !ready.isEmpty()) launch.run();
        else withPublicRelayNotice("Quick Connect",view,launch);
    }

    private static final class SwitchDestination {
        final String country, name, caption;
        final Runnable connect;

        SwitchDestination(String country,String name,String caption,Runnable connect) {
            this.country = country;
            this.name = name;
            this.caption = caption;
            this.connect = connect;
        }

        int match(String term) {
            if (country.isEmpty()) return LocationSearch.normalize(name).contains(term) ? 1 : 0;
            return Math.max(LocationSearch.match(country,VpnDirectory.name(country),term),
                    LocationSearch.normalize(name).contains(term) ? 1 : 0);
        }
    }

    private List<SwitchDestination> switchDestinations(View opener) {
        List<SwitchDestination> destinations = new ArrayList<>();
        edition.countries.forEach((country,relays) -> {
            String name = VpnDirectory.name(country);
            String excluded = currentRelayIp(country);
            if (relays.stream().noneMatch(relay -> !relay.ip.equals(excluded))) return;
            destinations.add(new SwitchDestination(country,name,excluded.isEmpty() ? "Quick Connect" : "Try a different relay",
                    () -> switchToCountry(country,opener)));
        });
        for (Imported profile : imported) {
            if (!isCurrent(profile.entry())) {
                String caption = profile.protocol() + (profile.needsLogin() ? " · Login required" : "");
                destinations.add(new SwitchDestination(profile.country,profile.name,caption,() -> connectImport(profile,opener)));
            }
        }
        for (int number = 1; number <= 2; number++) {
            int server = number;
            String id = "provider:PK:" + server;
            if (endpoint != null && endpoint.id.equals(id)) continue;
            destinations.add(new SwitchDestination("PK","Pakistan " + server,"Login required",() -> pakistanDialog(server,opener)));
        }
        destinations.sort(Comparator.comparing(
                (SwitchDestination destination) -> destination.name,String.CASE_INSENSITIVE_ORDER));
        return destinations;
    }

    private String currentRelayIp(String country) {
        String prefix = "relay:" + country + ":";
        return connected && endpoint != null && endpoint.id.startsWith(prefix)
                ? endpoint.id.substring(prefix.length()) : "";
    }

    private void switchToCountry(String country,View opener) {
        if (!canChooseDestination()) return;
        QuickSession session = new QuickSession(country,Collections.emptyList());
        String excluded = currentRelayIp(country);
        if (!excluded.isEmpty()) session.tried.add(excluded);
        String name = VpnDirectory.name(country);
        if (session.servers(edition).stream().noneMatch(relay -> !session.tried.contains(relay.ip))) {
            toast("No other relay is listed for " + name + ".");
            return;
        }
        withPublicRelayNotice((connected ? "Switch to " : "Connect to ") + name,opener,
                () -> chooseConnection(opener,name,() -> startQuickConnect(session)));
    }

    private void showSwitchLocation(View opener) {
        if (!canChooseDestination()) return;
        List<SwitchDestination> destinations = switchDestinations(opener);
        LinearLayout rows = dialogRows();
        EditText filter = new EditText(inputContext());
        filter.setHint("Search locations");
        filter.setSingleLine();
        filter.setTextSize(15);
        filter.setPadding(dp(12),0,dp(12),0);
        colorText(filter,LIGHT.ink,DARK.ink);
        filter.setHintTextColor(theme.muted);
        colorGlass(filter,GlassTheme.Surface.CONTROL,18);
        rows.addView(filter,new LinearLayout.LayoutParams(-1,dp(48)));
        gap(rows,8);

        Context context = inputContext();
        ListView list = new ListView(context);
        list.setSelector(android.R.color.transparent);
        list.setDivider(null);
        FrameLayout results = new FrameLayout(this);
        int height = Math.max(120,Math.min(320,getResources().getConfiguration().screenHeightDp / 2));
        rows.addView(results,new LinearLayout.LayoutParams(-1,dp(height)));
        results.addView(list,new FrameLayout.LayoutParams(-1,-1));
        TextView empty = text("No matching locations.",14,theme.muted,"sans-serif",Typeface.NORMAL);
        empty.setGravity(Gravity.CENTER);
        results.addView(empty,new FrameLayout.LayoutParams(-1,-1));
        ArrayAdapter<SwitchDestination> adapter = new ArrayAdapter<SwitchDestination>(context,android.R.layout.simple_list_item_1) {
            @Override public View getView(int position,View recycled,ViewGroup parent) {
                TextView row = (TextView)super.getView(position,recycled,parent);
                SwitchDestination destination = getItem(position);
                String label = destination.name + "\n" + destination.caption;
                android.text.SpannableString copy = new android.text.SpannableString(label);
                int start = destination.name.length() + 1;
                copy.setSpan(new android.text.style.RelativeSizeSpan(.78f),start,label.length(),0);
                copy.setSpan(new android.text.style.ForegroundColorSpan(theme.muted),start,label.length(),0);
                row.setText(copy);
                row.setTextColor(theme.ink);
                row.setTextSize(16);
                row.setPadding(dp(7),dp(8),dp(7),dp(8));
                row.setMinHeight(dp(54));
                BitmapDrawable flag = new BitmapDrawable(getResources(),countryIcon(destination.country));
                flag.setBounds(0,0,dp(32),dp(28));
                row.setCompoundDrawablesRelative(flag,null,null,null);
                row.setCompoundDrawablePadding(dp(12));
                return row;
            }
        };
        list.setAdapter(adapter);
        list.setEmptyView(empty);
        Runnable update = () -> {
            String term = LocationSearch.normalize(filter.getText().toString());
            List<SwitchDestination> matching = new ArrayList<>();
            for (SwitchDestination destination : destinations) if (destination.match(term) > 0) matching.add(destination);
            matching.sort(Comparator.comparingInt((SwitchDestination destination) -> destination.match(term)).reversed());
            adapter.setNotifyOnChange(false);
            adapter.clear();
            adapter.addAll(matching);
            adapter.notifyDataSetChanged();
            list.setSelection(0);
        };
        filter.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s,int start,int count,int after) {}
            @Override public void onTextChanged(CharSequence s,int start,int before,int count) { update.run(); }
            @Override public void afterTextChanged(Editable s) {}
        });
        update.run();
        rows.setFocusableInTouchMode(true);
        rows.requestFocus();
        AlertDialog picker = dialogBuilder().setTitle("Switch location").setView(rows)
                .setNegativeButton("Cancel",null).create();
        DialogMorph.Origin origin = DialogMorph.origin(opener);
        list.setOnItemClickListener((parent,view,position,id) -> {
            SwitchDestination destination = adapter.getItem(position);
            if (destination == null) return;
            InputMethodManager ime = getSystemService(InputMethodManager.class);
            if (ime != null) ime.hideSoftInputFromWindow(filter.getWindowToken(),0);
            closeDialog(picker,origin,destination.connect);
        });
        showAnchoredDialog(picker,origin,true);
    }

    private void endConnection(View view) {
        connectionHaptic(view,false);
        pendingSwitch = null;
        main.removeCallbacks(switchCheck);
        cancelReconnect();
        SavedConnection.clear(this);
        recovery = null;
        lastError = "";
        quick = null;
        if (wireGuard || pendingWireGuard != null) {
            pendingWireGuard = null;
            awaitingPermission = false;
            wireGuard = false;
            SetuVpnService.disconnect(this);
            message = "";
            clearStopped();
        } else if (stopping) {
            message = "Disconnected";
            renderState();
        } else stop("Disconnected");
    }

    private void connectionHaptic(View view,boolean connecting) {
        if (view == null) return;
        view.performHapticFeedback(Build.VERSION.SDK_INT >= 30
                ? (connecting ? HapticFeedbackConstants.CONFIRM : HapticFeedbackConstants.CONTEXT_CLICK)
                : HapticFeedbackConstants.VIRTUAL_KEY);
    }

    private boolean canChooseDestination() {
        if (pendingSwitch == null && !busy && !stopping && (!started || connected)) return true;
        toast("Wait for the current connection or tap Cancel.");
        return false;
    }

    private void chooseConnection(View opener,String destination,Runnable connect) {
        if (!canChooseDestination()) return;
        hideKeyboard();
        connectionHaptic(opener,true);
        if (!connected) {
            connect.run();
            return;
        }
        pendingSwitch = new PendingSwitch(destination,connect,wireGuard,connectedAt);
        cancelReconnect();
        SavedConnection.clear(this);
        recovery = null;
        lastError = "";
        String switching = "Switching to " + destination + "…";
        if (wireGuard) {
            wireGuard = false;
            SetuVpnService.disconnect(this);
            message = switching;
            clearStopped();
        } else stop(switching);
    }

    private void finishSwitch() {
        PendingSwitch request = pendingSwitch;
        if (request == null || !foreground || stopping || isDestroyed()) return;
        long now = SystemClock.elapsedRealtime();
        if (request.deadline == 0) request.deadline = now + 5000;
        if (ownsActiveTunnel()) {
            if (now < request.deadline) {
                main.postDelayed(switchCheck,150);
                return;
            }
            pendingSwitch = null;
            wireGuard = request.previousWireGuard;
            connectedAt = request.previousConnectedAt;
            busy = false;
            started = true;
            connected = true;
            message = "Couldn’t switch to " + request.destination + ". The previous tunnel is still active.";
            renderState();
            return;
        }
        pendingSwitch = null;
        busy = false;
        request.connect.run();
    }

    private boolean ownsActiveTunnel() {
        if (activeVpnNetwork(this) != null) return true;
        if (Build.VERSION.SDK_INT < 30) return false;
        ConnectivityManager manager = getSystemService(ConnectivityManager.class);
        if (manager == null) return false;
        for (Network network : manager.getAllNetworks()) {
            NetworkCapabilities caps = manager.getNetworkCapabilities(network);
            if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
                    && caps.getOwnerUid() == android.os.Process.myUid()) return true;
        }
        return false;
    }

    private boolean ownsCountryAction(String code) {
        if (quick != null) return code.equals(quick.country);
        return code.equals(displayCountry) && (busy || connected || started || stopping);
    }

    private void countryQuickAction(String code,View view) {
        if (ownsCountryAction(code)) {
            endConnection(view);
            return;
        }
        if (!canChooseDestination()) return;
        List<Imported> ready = importsFor(code);
        ready.removeIf(profile -> profile.needsLogin() || profile.wireGuard);
        QuickSession session = new QuickSession(code,ready);
        String name = VpnDirectory.name(code);
        Runnable launch = () -> chooseConnection(view,name,() -> startQuickConnect(session));
        if (!session.servers(edition).isEmpty()) {
            withPublicRelayNotice((connected ? "Switch to " : "Quick Connect to ") + name,view,launch);
        } else if (!ready.isEmpty()) launch.run();
        else showAnchoredDialog(dialogBuilder().setTitle("No ready servers for " + name)
                    .setMessage("Pull down at the top to refresh the directory, or use Settings to import an OpenVPN profile.")
                    .setPositiveButton("Done",null).create(),DialogMorph.origin(view),true);
    }

    private void startQuickConnect(QuickSession session) {
        quick = session;
        recovery = null;
        lastError = "";
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
            loadRelay(relay,name);
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
        recovery = Recovery.REFRESH;
        fail(session.country == null ? "None of the listed servers connected."
                : "No servers connected in " + VpnDirectory.name(session.country) + ".");
    }

    private void rankQuickServers(QuickSession session,VpnDirectory.Edition snapshot,List<VpnDirectory.Relay> servers) {
        Set<String> tried = new LinkedHashSet<>(session.tried);
        boolean global = session.country == null;
        String previous = global ? getPreferences(MODE_PRIVATE).getString(LAST_GLOBAL_COUNTRY,"") : "";
        session.rankedEdition = snapshot;
        String network = ConnectionHistory.network(this);
        profileTask = work.submit(() -> {
            Map<String,Integer> health = history.relayScores(network,System.currentTimeMillis());
            List<VpnDirectory.Relay> ordered = QuickConnectOrder.rank(
                    servers,tried,global,previous,ThreadLocalRandom.current().nextLong(),health);
            try {
                if (global) QuickConnectOrder.measureFirstCountry(ordered,health);
                else QuickConnectOrder.measureLatency(ordered,health);
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
        if (!canChooseDestination()) return;
        if (isCurrent(ConnectionHistory.Entry.relay(relay,name))) return;
        withPublicRelayNotice((connected ? "Switch to " : "Connect to ") + VpnDirectory.name(relay.country) + "?",view,
                () -> chooseConnection(view,name,() -> loadRelay(relay,name)));
    }

    private void loadRelay(VpnDirectory.Relay relay,String name) {
        loadProfile(ConnectionHistory.Entry.relay(relay,name),"","",() -> directory.profile(relay),
                "Couldn’t load this relay’s profile.",null);
    }

    private void withPublicRelayNotice(String title,View view,Runnable connect) {
        if (getPreferences(MODE_PRIVATE).getBoolean("public_notice",false)) {
            connect.run();
            return;
        }
        DialogMorph.Origin origin = DialogMorph.origin(view);
        AlertDialog dialog = dialogBuilder().setTitle(title)
                .setMessage("Setu VPN uses public VPN Gate relays. VPN Gate records connection logs. Relay operators can record packet headers.")
                .setNegativeButton("Cancel",null).setPositiveButton(connected ? "Switch" : "Connect",null).create();
        showAnchoredDialog(dialog,origin,true);
        bindDialogAction(dialog,AlertDialog.BUTTON_POSITIVE,origin,() -> {
            getPreferences(MODE_PRIVATE).edit().putBoolean("public_notice",true).apply();
            connect.run();
        });
    }

    private void pakistanDialog(int server,View opener) {
        if (!canChooseDestination()) return;
        ConnectionHistory.Entry target = new ConnectionHistory.Entry(
                "provider:PK:" + server,"Pakistan " + server,"PK","OpenVPN");
        if (isCurrent(target)) return;
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
                .setPositiveButton(connected ? "Switch" : "Connect",null).create();
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
            closeDialog(dialog,origin,() -> chooseConnection(opener,target.name,() -> loadProfile(target,user,secret,
                    () -> VpnDirectory.pakistanProfile(server),"Couldn’t load the Pakistan profile.",
                    () -> showPakistanImport(server))));
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

    private void loadProfile(ConnectionHistory.Entry entry,String username,String password,ProfileLoader loader,
                             String errorText,Runnable onError) {
        endpoint = entry;
        int attempt = begin(entry.name,entry.country);
        attemptNetwork = ConnectionHistory.network(this);
        successfulAttempt = false;
        if (!restoring) connectedAt = 0;
        attemptRecorded = false;
        if (!restoring) resumeConfig = null;
        profileTask = work.submit(() -> {
            try {
                String config = loader.load();
                VpnProfile profile = makeProfile(config,entry.name,username);
                main.post(() -> {
                    if (active(attempt)) {
                        profileTask = null;
                        if (username.isEmpty() && password.isEmpty() && !usesLogin(config)) resumeConfig = config;
                        prepare(profile,password);
                    }
                });
            } catch (Exception error) {
                main.post(() -> {
                    if (active(attempt)) {
                        attemptFailed(error instanceof java.net.SocketTimeoutException
                                ? "The profile download timed out." : errorText);
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

    private boolean isCurrent(ConnectionHistory.Entry entry) {
        return connected && !stopping && endpoint != null && endpoint.id.equals(entry.id);
    }

    private int begin(String name,String country) {
        displayName = name;
        displayCountry = country;
        recovery = null;
        if (quick == null && !supervisedReconnect) lastError = "";
        message = quick == null ? "Connecting to " + name + "…" : quickAttemptMessage();
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
        } catch (Exception error) {
            recovery = Recovery.ALLOW;
            fail("Android couldn’t open the VPN permission request.");
        }
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
                    if (active(attempt) && waitingForEngine) attemptFailed("The VPN service took too long to start.");
                },4000);
            }
            return;
        }
        pending = null;
        pendingPassword = "";
        waitingForEngine = false;
        try {
            if (!password.isEmpty()) engineStatus.setCachedPassword(profile.getUUIDString(),PasswordCache.AUTHPASSWORD,password);
            options.apply(profile);
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
                    if (active(attempt) && !connected) attemptFailed("The server responded but couldn’t finish connecting.");
                },NEGOTIATION_GRACE_MS);
            } else attemptFailed(displayName + " didn’t respond in time.");
        },TUNNEL_TIMEOUT_MS);
    }

    private void attemptFailed(String reason) {
        attemptFailed(reason,defaultRecovery());
    }

    private void attemptFailed(String reason,Recovery action) {
        lastError = reason;
        recovery = action;
        recordFailure();
        if (quick != null) stop("Trying another server… " + reason);
        else if (supervisedReconnect || restoring) connectionLost(reason);
        else if (started || connected) stop(reason);
        else fail(reason);
    }

    private void recordFailure() {
        if (!successfulAttempt && !attemptRecorded && endpoint != null && !"other".equals(attemptNetwork)) {
            attemptRecorded = true;
            history.record(endpoint,attemptNetwork,false);
        }
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
        busy = quick != null || pendingSwitch != null;
        started = false;
        connected = false;
        if (!supervisedReconnect) connectedAt = 0;
        startedObserved = false;
        activeProfileId = null;
        if (pendingSwitch != null) {
            main.removeCallbacks(switchCheck);
            main.postDelayed(switchCheck,150);
        } else if (quick != null) {
            main.post(this::advanceQuickConnect);
        } else if (supervisedReconnect) {
            busy = true;
            scheduleReconnect();
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
        connectedAt = 0;
        message = text;
        if (!text.isEmpty()) lastError = text;
        renderState();
    }

    @Override public void updateState(String raw,String log,int id,ConnectionStatus level,Intent intent) {
        String reportedProfile = VpnStatus.getLastConnectedVPNProfile();
        main.post(() -> {
            if (isDestroyed() || wireGuard || pendingWireGuard != null) return;
            if (stopping) {
                // EXITING can arrive before OpenVPN's native process releases the service.
                if (level == ConnectionStatus.LEVEL_NOTCONNECTED
                        && ((quick == null && pendingSwitch == null && !supervisedReconnect) || "NOPROCESS".equals(raw)))
                    clearStopped();
                return;
            }
            if (pendingSwitch != null || (profileTask != null && !started)) return;
            if (quick != null && !started) return;
            boolean currentProfile = activeProfileId != null && activeProfileId.equals(reportedProfile);
            if (activeProfileId != null && !currentProfile && level != ConnectionStatus.LEVEL_START) return;
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
                    if (connected) successfulAttempt = false;
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
                            : quick == null ? (displayName.isEmpty() ? "" : "Connecting to " + displayName + "…") : quickAttemptMessage();
                    break;
                case LEVEL_AUTH_FAILED:
                    if (started || connected) {
                        boolean edit = canEditCredentials();
                        attemptFailed(edit ? "The provider rejected the VPN username or password."
                                : "VPN authentication failed.",edit ? Recovery.EDIT : defaultRecovery());
                    }
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
                        if ("NOPROCESS".equals(raw)) {
                            lastError = "The relay stopped responding.";
                            message = "Trying another server… " + lastError;
                            recordFailure();
                            clearStopped();
                        } else attemptFailed("The VPN connection ended.");
                    }
                    else if (startedObserved || connected) {
                        boolean wasConnected = connected || connectedAt > 0;
                        started = false;
                        connected = false;
                        if (wasConnected) connectionLost("The VPN connection dropped.");
                        else attemptFailed("The relay stopped responding.");
                    }
                    return;
                default:
                    return;
            }
            renderState();
        });
    }

    private void verifyTunnel(int check,int polls) {
        if (check != tunnelCheck || stopping || !started || isDestroyed()) return;
        Network network = activeVpnNetwork(this);
        if (network != null) {
            if (quick != null && quick.country == null && !displayCountry.isEmpty()) {
                getPreferences(MODE_PRIVATE).edit().putString(LAST_GLOBAL_COUNTRY,displayCountry).apply();
            }
            quick = null;
            connected = true;
            busy = false;
            message = "";
            if (!successfulAttempt) {
                successfulAttempt = true;
                connectedAt = SystemClock.elapsedRealtime();
                lastError = "";
                recovery = null;
                if (endpoint == null) {
                    SavedConnection saved = SavedConnection.read(this);
                    if (saved != null && "OpenVPN".equals(saved.entry.protocol)) {
                        endpoint = saved.entry;
                        displayName = endpoint.name;
                        displayCountry = endpoint.country;
                        resumeConfig = saved.config;
                    }
                }
                history.record(endpoint,"other".equals(attemptNetwork)
                        ? ConnectionHistory.network(this) : attemptNetwork,true);
                updateLastServers();
                if (resumeConfig != null) SavedConnection.remember(this,endpoint,resumeConfig);
                else SavedConnection.clear(this);
            }
            cancelReconnect();
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
        boolean session = connected && pendingSwitch == null && !stopping && !trying;
        boolean showError = !session && !busy && !stopping && !trying && recovery != null && !lastError.isEmpty();
        String country = displayCountry.isEmpty() ? "" : VpnDirectory.name(displayCountry);
        String location = country.isEmpty() ? displayName : country;
        String heading = session ? wireGuard && !successfulAttempt ? "Tunnel ready"
                : location.isEmpty() ? "Connected" : "Connected to " + location
                : pendingSwitch != null ? "Switching location"
                : stopping && !trying ? "Disconnecting"
                : busy || trying ? supervisedReconnect ? "Reconnecting" : "Connecting"
                : showError ? "Couldn’t connect" : "Disconnected";
        transitionText(state,heading);
        state.setTextColor(ThemeTransition.color(LIGHT.ink,Color.WHITE,colors.amount()));
        String subtitle = !message.isEmpty() ? message : session ? "Connection details" : busy ? displayName : "";
        transitionText(detail,subtitle);
        detail.setVisibility(subtitle.isEmpty() ? View.GONE : View.VISIBLE);
        View overview = (View)state.getParent();
        overview.setClickable(session);
        overview.setFocusable(session);
        overview.setContentDescription(session ? heading + ". " + subtitle
                + ("Connection details".equals(subtitle) ? "" : ". Connection details") : null);
        sessionActions.setVisibility(session ? View.VISIBLE : View.GONE);
        recoveryButton.setVisibility(showError ? View.VISIBLE : View.GONE);
        if (showError) recoveryButton.setText(recovery.label);
        transitionText(action,trying || pendingSwitch != null ? "Cancel"
                : stopping ? "Disconnecting…" : session ? "Switch location" : busy ? "Cancel" : "Quick Connect");
        action.setEnabled(!stopping || trying || pendingSwitch != null);
        countryActions.forEach(this::renderCountryAction);
        String activeId = session && endpoint != null ? endpoint.id : null;
        if (showingSwitchActions != session || !Objects.equals(markedEndpoint,activeId)) {
            showingSwitchActions = session;
            markedEndpoint = activeId;
            refreshServerActions(locations);
        }
        syncSessionClock();
        animateConnectionMeter();
    }

    private enum Recovery {
        NEXT("Try next server"), EDIT("Edit credentials"), RETRY("Try again"),
        ALLOW("Request VPN permission"), REFRESH("Refresh servers");

        final String label;
        Recovery(String label) { this.label = label; }
    }

    private Recovery defaultRecovery() {
        if (endpoint == null) return Recovery.REFRESH;
        if (endpoint.id.startsWith("relay:")) return Recovery.NEXT;
        return Recovery.RETRY;
    }

    private boolean canEditCredentials() {
        if (endpoint == null) return false;
        if (endpoint.id.startsWith("provider:PK:")) return true;
        for (Imported profile : imported) {
            if (endpoint.id.equals(profile.entry().id)) return profile.needsLogin();
        }
        return false;
    }

    private void recoverConnection(View opener) {
        if (recovery == null) return;
        if (recovery == Recovery.REFRESH) {
            recovery = null;
            lastError = "";
            message = "";
            renderState();
            if (ValueAnimator.areAnimatorsEnabled()) scroll.smoothScrollTo(0,0);
            else scroll.scrollTo(0,0);
            pullRefresh();
        } else if (recovery == Recovery.NEXT && endpoint != null) {
            String id = endpoint.id;
            String ip = id.substring(id.lastIndexOf(':') + 1);
            String country = listedRelays(endpoint.country).stream().anyMatch(relay -> !relay.ip.equals(ip))
                    ? endpoint.country : null;
            QuickSession session = new QuickSession(country,Collections.emptyList());
            session.tried.add(ip);
            withPublicRelayNotice("Try another server",opener,
                    () -> chooseConnection(opener,"another server",() -> startQuickConnect(session)));
        } else if (endpoint != null) connectSaved(endpoint,opener);
        else heroAction(opener);
    }

    private void syncSessionClock() {
        main.removeCallbacks(sessionTick);
        tickSession();
    }

    private void tickSession() {
        if (sessionClock == null) return;
        boolean running = connected && !stopping && pendingSwitch == null && successfulAttempt && connectedAt != 0;
        sessionClock.setVisibility(running ? View.VISIBLE : View.GONE);
        if (!running) return;
        sessionClock.setText(sessionDuration());
        if (foreground) main.postDelayed(sessionTick,1000);
    }

    private String sessionDuration() {
        if (!connected || !successfulAttempt || connectedAt == 0) return "—";
        long elapsed = Math.max(0,SystemClock.elapsedRealtime() - connectedAt) / 1000;
        return String.format(Locale.ROOT,"%02d:%02d:%02d",elapsed/3600,(elapsed/60)%60,elapsed%60);
    }

    private void renderCountryAction(String code,TextView toggle) {
        boolean active = ownsCountryAction(code);
        String label = !active ? (connected && !stopping && pendingSwitch == null ? "Switch here" : "Quick Connect")
                : quick != null ? "Cancel" : stopping ? "Disconnecting…"
                : connected ? "Disconnect" : "Cancel";
        transitionText(toggle,label);
        toggle.setContentDescription(label + " for " + VpnDirectory.name(code));
        toggle.setEnabled(!active || !stopping || quick != null);
    }

    private static final class Imported {
        final String name, country, config;
        final File file;
        final boolean wireGuard;
        Imported(String n,String c,String p,File f,boolean wireGuard) {
            name = n; country = c; config = p; file = f; this.wireGuard = wireGuard;
        }

        boolean needsLogin() { return !wireGuard && usesLogin(config); }

        String protocol() { return wireGuard ? "WireGuard" : "OpenVPN"; }

        ConnectionHistory.Entry entry() {
            return new ConnectionHistory.Entry("import:" + file.getName(),name,country,protocol());
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
                list.add(new Imported(data.getString("name"),data.getString("country"),data.getString("config"),file,data.optBoolean("wireguard")));
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
            if (result == RESULT_OK) {
                if (pendingWireGuard != null) startWireGuard();
                else startTunnel();
            } else {
                pendingWireGuard = null;
                wireGuard = false;
                recovery = Recovery.ALLOW;
                fail("VPN permission was declined.");
            }
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
                for (OpenVpnFiles.Profile profile : profiles) {
                    if (!profile.wireGuard) makeProfile(profile.config,profile.name,"");
                }
                for (OpenVpnFiles.Profile profile : profiles) saved.add(saveImport(profile,country));
                main.post(() -> {
                    if (isDestroyed()) return;
                    imported.addAll(saved);
                    expanded.add(country);
                    renderLocations();
                    reveal(locations,7,270,0.7f,0);
                    toast(saved.size() == 1 ? "VPN profile imported." : saved.size() + " VPN profiles imported.");
                });
            } catch (Exception error) {
                for (Imported profile : saved) new AtomicFile(profile.file).delete();
                main.post(() -> {
                    if (isDestroyed()) return;
                    String why = error.getMessage();
                    showDialog(dialogBuilder().setTitle("Couldn’t import profile")
                            .setMessage(why != null && why.length() < 160 ? why : "Choose an OpenVPN .ovpn file, WireGuard .conf file, or ZIP of profiles.")
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
                .put("country",country).put("config",profile.config).put("wireguard",profile.wireGuard).toString());
        FileOutputStream out = atomic.startWrite();
        try {
            out.write(bytes);
            atomic.finishWrite(out);
        } catch (Exception error) {
            atomic.failWrite(out);
            throw error;
        }
        return new Imported(profile.name,country,profile.config,file,profile.wireGuard);
    }

    private void connectImport(Imported profile,View opener) {
        if (!canChooseDestination() || isCurrent(profile.entry())) return;
        if (profile.wireGuard) {
            chooseConnection(opener,profile.name,() -> connectWireGuard(profile));
            return;
        }
        if (!profile.needsLogin()) {
            chooseConnection(opener,profile.name,() -> connectImport(profile,"",""));
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
        boolean editing = recovery == Recovery.EDIT && endpoint != null && endpoint.id.equals(profile.entry().id);
        AlertDialog dialog = dialogBuilder().setTitle(editing ? "Edit credentials" : "Provider login").setView(fields)
                .setNegativeButton("Cancel",null).setPositiveButton(connected ? "Switch" : "Connect",null).create();
        DialogMorph.Origin origin = DialogMorph.origin(opener);
        showAnchoredDialog(dialog,origin,true);
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String username = user.getText().toString().trim();
            String secret = password.getText().toString();
            if (username.isEmpty() || secret.isEmpty()) {
                toast("Enter your VPN username and password.");
                return;
            }
            closeDialog(dialog,origin,() -> chooseConnection(opener,profile.name,
                    () -> connectImport(profile,username,secret)));
        });
    }

    private void connectImport(Imported profile,String username,String password) {
        loadProfile(profile.entry(),username,password,() -> profile.config,
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
            history.remove(profile.entry());
            lastByCountry = history.lastByCountry();
            SavedConnection saved = SavedConnection.read(this);
            if (saved != null && saved.entry.id.equals(profile.entry().id)) SavedConnection.clear(this);
            renderLocations();
            reveal(locations,-4,230,0.75f,0);
        });
    }

    private void showSaved(AlertDialog menu,boolean favorites,View opener) {
        List<ConnectionHistory.Entry> entries = favorites ? history.favorites() : history.recent();
        LinearLayout rows = dialogRows();
        if (entries.isEmpty()) paragraph(rows,favorites ? "Tap a star beside a server to save it."
                : "Successful connections will appear here.");
        AlertDialog dialog = dialogBuilder().setTitle(favorites ? "Favorites" : "Recent connections")
                .setView(scrollDialog(rows)).setPositiveButton("Done",null).create();
        DialogMorph.Origin origin = DialogMorph.origin(opener);
        for (ConnectionHistory.Entry entry : entries) {
            ServerTile tile = (ServerTile)addTile(rows,entry.name,entry.protocol,"Connect",
                    view -> closeDialog(dialog,origin,() -> {
                        menu.dismiss();
                        connectSaved(entry,settingsButton);
                    }));
            tile.favorite(entry);
        }
        showAnchoredDialog(dialog,origin,true);
        bindDialogAction(dialog,AlertDialog.BUTTON_POSITIVE,origin,null);
        bindDialogBack(dialog,() -> closeDialog(dialog,origin,null));
    }

    private void connectSaved(ConnectionHistory.Entry entry,View opener) {
        if (!canChooseDestination() || isCurrent(entry)) return;
        for (Imported profile : imported) {
            if (entry.id.equals(profile.entry().id)) {
                connectImport(profile,opener);
                return;
            }
        }
        List<VpnDirectory.Relay> relays = listedRelays(entry.country);
        for (VpnDirectory.Relay relay : relays) {
            if (entry.id.equals(ConnectionHistory.Entry.relay(relay,entry.name).id)) {
                connectRelay(relay,entry.name,opener);
                return;
            }
        }
        if (entry.id.startsWith("provider:PK:")) {
            pakistanDialog(entry.id.endsWith(":2") ? 2 : 1,opener);
            return;
        }
        toast("This relay is unavailable. Refresh the directory and try again.");
    }

    private LinearLayout dialogRows() {
        LinearLayout content = column();
        content.setPadding(dp(16),dp(6),dp(16),dp(12));
        return content;
    }

    private ScrollView scrollDialog(LinearLayout content) {
        ScrollView scroll = new ScrollView(this);
        scroll.addView(content,new ScrollView.LayoutParams(-1,-2));
        return scroll;
    }

    private void showSplitTunneling(View opener) {
        DialogMorph.Origin origin = DialogMorph.origin(opener);
        Set<String> selected = options.excluded();
        Intent launcher = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> apps = getPackageManager().queryIntentActivities(launcher,0);
        Map<String,String> names = new HashMap<>();
        for (ResolveInfo app : apps) {
            String pack = app.activityInfo.packageName;
            if (!pack.equals(getPackageName())) names.put(pack,app.loadLabel(getPackageManager()).toString());
        }
        List<String> packages = new ArrayList<>(names.keySet());
        packages.sort(Comparator.comparing(name -> names.get(name).toLowerCase(Locale.ROOT)));
        LinearLayout rows = dialogRows();
        paragraph(rows,"Select apps to exclude. Changes apply the next time you connect. Android’s kill switch may block excluded apps.");
        for (String pack : packages) {
            Switch pick = settingsSwitch(rows,names.get(pack),selected.contains(pack));
            pick.setOnCheckedChangeListener((button,exclude) -> {
                if (exclude) selected.add(pack);
                else selected.remove(pack);
            });
        }
        AlertDialog dialog = dialogBuilder().setTitle("Split tunneling").setView(scrollDialog(rows))
                .setNegativeButton("Cancel",null).setPositiveButton("Save",null).create();
        showAnchoredDialog(dialog,origin,true);
        bindDialogAction(dialog,AlertDialog.BUTTON_POSITIVE,origin,() -> {
            options.setExcluded(selected);
            if (connected || busy) toast("Reconnect to apply your split tunneling choices.");
        });
        bindDialogAction(dialog,AlertDialog.BUTTON_NEGATIVE,origin,null);
        bindDialogBack(dialog,() -> closeDialog(dialog,origin,null));
    }

    private void showKillSwitch(View opener) {
        DialogMorph.Origin origin = DialogMorph.origin(opener);
        SavedConnection saved = SavedConnection.read(this);
        String current = saved == null ? "Connect once to a profile that can reconnect without a password."
                : "Setu can restore " + saved.entry.name + " when Android starts the VPN.";
        if (!options.excluded().isEmpty()) current += "\n\nThe kill switch may block apps excluded by split tunneling.";
        AlertDialog dialog = dialogBuilder().setTitle("Kill switch")
                .setMessage("In Android’s VPN settings, open Setu VPN’s gear menu. Turn on Always-on VPN and Block connections without VPN.\n\n" + current)
                .setNegativeButton("Back",null).setPositiveButton("Android VPN settings",null).create();
        showAnchoredDialog(dialog,origin,true);
        bindDialogAction(dialog,AlertDialog.BUTTON_POSITIVE,origin,() -> {
            try { startActivity(new Intent(Settings.ACTION_VPN_SETTINGS)); }
            catch (Exception ignored) { toast("Open VPN settings in Android Settings."); }
        });
        bindDialogAction(dialog,AlertDialog.BUTTON_NEGATIVE,origin,null);
        bindDialogBack(dialog,() -> closeDialog(dialog,origin,null));
    }

    private void showConnectionDetails(View opener) {
        LinearLayout rows = dialogRows();
        settingsValue(rows,"Status").setText(connected
                ? wireGuard && !successfulAttempt ? "Awaiting peer" : "Connected"
                : busy ? "Connecting" : "Disconnected");
        if (endpoint != null) {
            settingsValue(rows,"Server").setText(endpoint.name);
            settingsValue(rows,"Protocol").setText(endpoint.protocol);
            if (!endpoint.country.isEmpty()) settingsValue(rows,"Country").setText(VpnDirectory.name(endpoint.country));
        }
        TextView duration = settingsValue(rows,"Session duration");
        TextView ip = settingsValue(rows,"VPN exit IP");
        ip.setText("Not checked");
        if (!lastError.isEmpty()) paragraph(rows,lastError);
        TextView check = actionLabel("Check exit IP",14,theme.accent);
        check.setMinimumHeight(dp(48));
        check.setGravity(Gravity.CENTER);
        check.setEnabled(connected && activeVpnNetwork(this) != null);
        rows.addView(check,new LinearLayout.LayoutParams(-1,-2));
        paragraph(rows,"Checking uses ipify over the VPN and shares the VPN’s public IP with that service.");
        AlertDialog dialog = dialogBuilder().setTitle("Connection details").setView(scrollDialog(rows))
                .setPositiveButton("Done",null).create();
        DialogMorph.Origin origin = DialogMorph.origin(opener);
        check.setOnClickListener(view -> checkExitIp(dialog,check,ip));
        Runnable ticker = new Runnable() {
            @Override public void run() {
                if (!dialog.isShowing() || isDestroyed()) return;
                duration.setText(sessionDuration());
                main.postDelayed(this,1000);
            }
        };
        showAnchoredDialog(dialog,origin,true);
        ticker.run();
        dialog.setOnDismissListener(d -> main.removeCallbacks(ticker));
        bindDialogAction(dialog,AlertDialog.BUTTON_POSITIVE,origin,null);
        bindDialogBack(dialog,() -> closeDialog(dialog,origin,null));
    }

    private void checkExitIp(AlertDialog dialog,View button,TextView result) {
        Network network = activeVpnNetwork(this);
        if (!connected || network == null) {
            result.setText("VPN unavailable");
            return;
        }
        button.setEnabled(false);
        result.setText("Checking…");
        work.execute(() -> {
            String status = "Unavailable";
            try {
                java.net.HttpURLConnection link = (java.net.HttpURLConnection)network.openConnection(
                        new java.net.URL("https://api64.ipify.org/"));
                link.setConnectTimeout(4500);
                link.setReadTimeout(4500);
                link.setInstanceFollowRedirects(false);
                try {
                    if (link.getResponseCode() == 200) {
                        try (InputStream response = link.getInputStream()) {
                            String candidate = VpnDirectory.utf8(VpnDirectory.read(response,128)).trim();
                            if (candidate.matches("[0-9a-fA-F:.]{3,45}") && numericIp(candidate)) status = candidate;
                        }
                    }
                } finally { link.disconnect(); }
            } catch (Exception ignored) {}
            String display = status;
            main.post(() -> {
                if (!dialog.isShowing() || isDestroyed()) return;
                boolean same = connected && network.equals(activeVpnNetwork(this));
                result.setText(same ? display : "VPN changed");
                button.setEnabled(same);
            });
        });
    }

    private static boolean numericIp(String value) {
        if (value.indexOf(':') >= 0) {
            try { return java.net.InetAddress.getByName(value) instanceof java.net.Inet6Address; }
            catch (java.net.UnknownHostException ignored) { return false; }
        }
        String[] parts = value.split("\\.",-1);
        if (parts.length != 4) return false;
        for (String part : parts) {
            if (!part.matches("[0-9]{1,3}") || Integer.parseInt(part) > 255) return false;
        }
        return true;
    }

    static Network activeVpnNetwork(Context context) {
        ConnectivityManager manager = context.getSystemService(ConnectivityManager.class);
        if (manager == null) return null;
        Network network = manager.getActiveNetwork();
        NetworkCapabilities caps = network == null ? null : manager.getNetworkCapabilities(network);
        if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
                && (Build.VERSION.SDK_INT < 30 || caps.getOwnerUid() == android.os.Process.myUid())) return network;
        return null;
    }

    private static boolean usesLogin(String config) {
        return config.contains("\nauth-user-pass\n") || config.startsWith("auth-user-pass\n");
    }

    private void connectWireGuard(Imported profile) {
        endpoint = profile.entry();
        successfulAttempt = false;
        lastError = "";
        wireGuard = true;
        pendingWireGuard = profile;
        begin(profile.name,profile.country);
        try {
            Intent permission = VpnService.prepare(this);
            if (permission == null) startWireGuard();
            else {
                awaitingPermission = true;
                startActivityForResult(permission,VPN_PERMISSION);
            }
        } catch (RuntimeException error) {
            pendingWireGuard = null;
            wireGuard = false;
            recovery = Recovery.ALLOW;
            fail("Android couldn’t request VPN permission.");
        }
    }

    private void startWireGuard() {
        Imported profile = pendingWireGuard;
        pendingWireGuard = null;
        if (profile == null) return;
        requestVpnNotification();
        SetuVpnService.connect(this,profile.entry(),profile.config);
    }

    private void onWireGuardChanged() {
        SetuVpnService.Snapshot tunnel = SetuVpnService.snapshot();
        if (tunnel.state == SetuVpnService.State.IDLE) {
            if (wireGuard && pendingWireGuard == null && tunnel.request != wireRequest) {
                wireGuard = false;
                wireRequest = tunnel.request;
                clearStopped();
            }
            return;
        }
        if (tunnel.state == SetuVpnService.State.ERROR) {
            if (wireGuard || (tunnel.request != wireRequest && !started && !connected
                    && quick == null && pendingSwitch == null && profileTask == null)) {
                wireRequest = tunnel.request;
                wireGuard = false;
                if (tunnel.entry != null) endpoint = tunnel.entry;
                lastError = tunnel.message;
                recovery = defaultRecovery();
                fail(tunnel.message);
            }
            return;
        }
        boolean firstHandshake = tunnel.handshake && (wireRequest != tunnel.request || !successfulAttempt);
        wireRequest = tunnel.request;
        wireGuard = true;
        endpoint = tunnel.entry;
        displayName = endpoint.name;
        displayCountry = endpoint.country;
        started = true;
        busy = tunnel.state == SetuVpnService.State.STARTING;
        connected = tunnel.state == SetuVpnService.State.UP;
        successfulAttempt = tunnel.handshake;
        connectedAt = tunnel.since;
        message = tunnel.message;
        if (tunnel.handshake) {
            recovery = null;
            lastError = "";
            if (firstHandshake) updateLastServers();
        }
        renderState();
    }

    private void connectionLost(String reason) {
        lastError = reason;
        recovery = defaultRecovery();
        if (options.autoReconnect() && resumeConfig != null && endpoint != null
                && (connectedAt > 0 || supervisedReconnect || restoring) && reconnectCount < 3
                && VpnService.prepare(this) == null) {
            supervisedReconnect = true;
            if (started && !stopping) stop("Reconnecting…");
            else {
                busy = true;
                scheduleReconnect();
                renderState();
            }
        } else {
            cancelReconnect();
            if (started || connected) stop(reason);
            else fail(reason);
        }
    }

    private void scheduleReconnect() {
        main.removeCallbacks(reconnect);
        message = "Reconnecting… " + lastError;
        if (foreground) main.postDelayed(reconnect,1500L << reconnectCount);
    }

    private void resumeOpenVpn() {
        if (!supervisedReconnect || isDestroyed()) return;
        if (!foreground || isFinishing()) return;
        if (!options.autoReconnect() || reconnectCount >= 3 || VpnService.prepare(this) != null) {
            cancelReconnect();
            fail(lastError);
            return;
        }
        reconnectCount++;
        String profile = resumeConfig;
        ConnectionHistory.Entry entry = endpoint;
        restoring = true;
        loadProfile(entry,"","",() -> profile,"The saved OpenVPN profile couldn’t reconnect.",null);
    }

    private void cancelReconnect() {
        supervisedReconnect = false;
        restoring = false;
        reconnectCount = 0;
        main.removeCallbacks(reconnect);
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
        MeterState status = connectionMeterState();
        boolean moving = foreground && status.pulsing && ValueAnimator.areAnimatorsEnabled();
        meter.setVisibility(status == MeterState.IDLE ? View.GONE : View.VISIBLE);
        meter.setState(status);
        if (moving && connectionMotion == null) {
            connectionMotion = ValueAnimator.ofFloat(0f,1f);
            connectionMotion.setDuration(1700);
            connectionMotion.setInterpolator(new LinearInterpolator());
            connectionMotion.setRepeatCount(ValueAnimator.INFINITE);
            connectionMotion.addUpdateListener(animation -> meter.setPhase((float) animation.getAnimatedValue()));
            connectionMotion.start();
        } else if (!moving && connectionMotion != null) {
            connectionMotion.cancel();
            connectionMotion = null;
            meter.setPhase(0f);
        }
    }

    private MeterState connectionMeterState() {
        if (pendingSwitch != null) return MeterState.SWITCHING;
        if (awaitingPermission) return MeterState.PERMISSION;
        if (stopping) return quick == null ? MeterState.DISCONNECTING : MeterState.SEARCHING;
        if (connected) return successfulAttempt ? MeterState.CONNECTED : MeterState.AWAITING_PEER;
        if (supervisedReconnect) return MeterState.RECONNECTING;
        if (quick != null && !started) return MeterState.SEARCHING;
        if (busy || started) return MeterState.CONNECTING;
        if (!lastError.isEmpty() && (recovery != null || lastError.equals(message))) return MeterState.FAILED;
        return MeterState.IDLE;
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
            slide.setDuration(open ? 255 : 195);
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

    private enum MeterState {
        IDLE(0xffa8b6ce,0xff384258,false),
        SEARCHING(0xff6f52b4,0xffbfacff,true),
        CONNECTING(0xff235ab4,0xff8cbaff,true),
        SWITCHING(0xff884583,0xffe3a5df,true),
        PERMISSION(0xff865805,0xffffce79,true),
        AWAITING_PEER(0xff327388,0xff8de3f0,true),
        RECONNECTING(0xffa65522,0xffffb27e,true),
        DISCONNECTING(0xff59677c,0xffa9b8cc,true),
        FAILED(0xffb52b38,0xffff707c,false),
        CONNECTED(LIGHT.connected,DARK.connected,false);

        final int light, dark;
        final boolean pulsing;

        MeterState(int light,int dark,boolean pulsing) {
            this.light = light;
            this.dark = dark;
            this.pulsing = pulsing;
        }
    }

    private final class ConnectionMeter extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private MeterState state = MeterState.IDLE;
        private float phase;

        ConnectionMeter() {
            super(PaperlineActivity.this);
            setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        }

        void setState(MeterState state) {
            if (this.state == state) return;
            this.state = state;
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
            int tint = ThemeTransition.color(state.light,state.dark,colors.amount());
            if (state.pulsing && foreground && ValueAnimator.areAnimatorsEnabled()) {
                int track = ThemeTransition.color(LIGHT.meterTrack,DARK.meterTrack,colors.amount());
                float pulse = (float)Math.sin(Math.PI * phase);
                tint = ThemeTransition.color(track,tint,0.42f + 0.58f * pulse);
            }
            paint.setColor(tint);
            canvas.drawRoundRect(0,0,width,height,radius,radius,paint);
        }
    }
}
