package com.paperline.vpn;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import com.wireguard.android.backend.GoBackend;
import com.wireguard.android.backend.Statistics;
import com.wireguard.android.backend.Tunnel;
import com.wireguard.config.Config;

import de.blinkt.openvpn.VpnProfile;
import de.blinkt.openvpn.core.ConfigParser;
import de.blinkt.openvpn.core.ProfileManager;
import de.blinkt.openvpn.core.VPNLaunchHelper;

import java.io.StringReader;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** System entry point for always-on VPN and foreground owner of the WireGuard tunnel. */
public final class SetuVpnService extends GoBackend.VpnService {
    private static final String START = "com.paperline.vpn.WIREGUARD_START";
    private static final String STOP = "com.paperline.vpn.WIREGUARD_STOP";
    private static final String CHANNEL = "setu-wireguard";
    private static final int NOTIFICATION = 8023;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    enum State { IDLE, STARTING, UP, ERROR }

    static final class Snapshot {
        final State state;
        final ConnectionHistory.Entry entry;
        final long since;
        final boolean handshake;
        final String message;
        final long request;

        Snapshot(State state,ConnectionHistory.Entry entry,long since,boolean handshake,String message,long request) {
            this.state = state; this.entry = entry; this.since = since;
            this.handshake = handshake; this.message = message; this.request = request;
        }
    }

    private static volatile Snapshot snapshot = new Snapshot(State.IDLE,null,0,false,"",0);
    private static long requestCount;
    private static volatile Request next;
    private static Runnable listener;

    private static final class Request {
        final long token;
        final ConnectionHistory.Entry entry;
        final String config;

        Request(long token,ConnectionHistory.Entry entry,String config) {
            this.token = token; this.entry = entry; this.config = config;
        }
    }

    static Snapshot snapshot() { return snapshot; }

    static void listen(Runnable callback) { listener = callback; }

    static void unlisten(Runnable callback) { if (listener == callback) listener = null; }

    private static void publish(Snapshot value) {
        snapshot = value;
        if (listener != null) listener.run();
    }

    static void connect(Context context,ConnectionHistory.Entry entry,String config) {
        Request request = new Request(++requestCount,entry,config);
        next = request;
        publish(new Snapshot(State.STARTING,entry,0,false,"Starting WireGuard…",request.token));
        try {
            context.startForegroundService(new Intent(context,SetuVpnService.class).setAction(START));
        } catch (RuntimeException error) {
            next = null;
            publish(new Snapshot(State.ERROR,entry,0,false,"Android couldn’t start the WireGuard service.",request.token));
        }
    }

    static void disconnect(Context context) {
        long token = ++requestCount;
        next = null;
        SavedConnection.clear(context);
        publish(new Snapshot(State.IDLE,null,0,false,"",token));
        context.stopService(new Intent(context,SetuVpnService.class));
    }

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private GoBackend backend;
    private Tunnel tunnel;
    private Request active;
    private long startedAt;
    private boolean confirmed;
    private boolean destroyed;
    private boolean replacing;
    private boolean retryScheduled;
    private int retries;
    private ConnectivityManager networkManager;
    private ConnectivityManager.NetworkCallback callback;
    private Network underlying;
    private final Runnable poll = this::checkHandshake;
    private final Runnable resume = this::reconnect;
    private final Runnable networkChange = this::checkNetworkChange;

    @Override public int onStartCommand(Intent intent,int flags,int startId) {
        if (intent != null && STOP.equals(intent.getAction())) {
            disconnect(this);
            return START_NOT_STICKY;
        }
        // This explicit call completes GoBackend's service reference before its worker starts.
        super.onStartCommand(intent,flags,startId);
        startForeground(NOTIFICATION,notification("Connecting…"));
        Request request = next;
        next = null;
        if (intent != null && START.equals(intent.getAction())) {
            if (request == null) stopSelf(startId);
            else startWireGuard(request);
            return START_NOT_STICKY;
        }
        if (active != null) return START_STICKY;
        if (Build.VERSION.SDK_INT >= 29 && !isAlwaysOn()) {
            stopSelf(startId);
            return START_NOT_STICKY;
        }
        SavedConnection last = SavedConnection.read(this);
        if (last == null || prepare(this) != null) {
            stopSelf(startId);
            return START_NOT_STICKY;
        }
        if ("WireGuard".equals(last.entry.protocol)) {
            Request restored = new Request(++requestCount,last.entry,last.config);
            startWireGuard(restored);
            return START_STICKY;
        }
        worker.execute(() -> {
            try {
                if (PaperlineActivity.activeVpnNetwork(this) != null) return;
                ConfigParser parser = new ConfigParser();
                parser.parseConfig(new StringReader(last.config));
                VpnProfile profile = parser.convertProfile();
                profile.mName = last.entry.name;
                profile.mUseDefaultRoute = true;
                profile.mUseDefaultRoutev6 = true;
                profile.mAllowLocalLAN = false;
                profile.mBlockUnusedAddressFamilies = true;
                new VpnOptions(this).apply(profile);
                ProfileManager.setTemporaryProfile(this,profile);
                VPNLaunchHelper.startOpenVpn(profile,this,"Android always-on VPN",false);
            } catch (Exception ignored) {
                // Android's lockdown stays active if a saved tunnel cannot be restored.
            } finally {
                MAIN.post(() -> stopSelf(startId));
            }
        });
        return START_NOT_STICKY;
    }

    private void startWireGuard(Request request) {
        MAIN.removeCallbacks(poll);
        MAIN.removeCallbacks(resume);
        retryScheduled = false;
        if (active != request) retries = 0;
        active = request;
        confirmed = false;
        startedAt = 0;
        publish(new Snapshot(State.STARTING,request.entry,0,false,"Starting WireGuard…",request.token));
        updateNotification("Connecting to " + request.entry.name);
        observeNetwork();
        worker.execute(() -> {
            try {
                if (backend == null) backend = new GoBackend(getApplicationContext());
                Config config = WireGuardProfiles.apply(WireGuardProfiles.parse(request.config),new VpnOptions(this));
                if (tunnel == null) {
                    tunnel = new Tunnel() {
                        @Override public String getName() { return "Setu-WireGuard"; }
                        @Override public void onStateChange(Tunnel.State state) {
                            if (state != Tunnel.State.DOWN) return;
                            MAIN.post(() -> {
                                if (!destroyed && !replacing && active != null && snapshot.state == SetuVpnService.State.UP) {
                                    failed(active,"The WireGuard tunnel stopped.");
                                }
                            });
                        }
                    };
                }
                backend.setState(tunnel,Tunnel.State.UP,config);
                MAIN.post(() -> {
                    if (!current(request)) return;
                    replacing = false;
                    startedAt = SystemClock.elapsedRealtime();
                    publish(new Snapshot(State.UP,request.entry,startedAt,false,
                            "Tunnel ready · awaiting peer traffic",request.token));
                    updateNotification("Tunnel ready · " + request.entry.name);
                    checkHandshake();
                });
            } catch (Exception | LinkageError error) {
                MAIN.post(() -> failed(request,wireGuardError(error)));
            }
        });
    }

    private static String wireGuardError(Throwable error) {
        if (error instanceof com.wireguard.android.backend.BackendException) {
            String reason = ((com.wireguard.android.backend.BackendException)error).getReason().name();
            if ("DNS_RESOLUTION_FAILURE".equals(reason)) return "Couldn’t resolve the WireGuard endpoint.";
            if ("VPN_NOT_AUTHORIZED".equals(reason)) return "Android VPN permission is required.";
            if ("TUN_CREATION_ERROR".equals(reason)) return "Android couldn’t create the WireGuard tunnel.";
        }
        if (error instanceof java.io.IOException && error.getMessage() != null) return error.getMessage();
        return "Couldn’t start WireGuard. Check the profile and peer endpoint.";
    }

    private boolean current(Request request) {
        return !destroyed && active == request && requestCount == request.token;
    }

    private void checkHandshake() {
        Request request = active;
        if (request == null || !current(request) || confirmed || replacing) return;
        worker.execute(() -> {
            boolean success = false;
            try {
                Statistics stats = backend.getStatistics(tunnel);
                for (com.wireguard.crypto.Key peer : stats.peers()) {
                    Statistics.PeerStats data = stats.peer(peer);
                    if (data != null && data.latestHandshakeEpochMillis() > 0) success = true;
                }
            } catch (RuntimeException ignored) {}
            final boolean handshook = success;
            MAIN.post(() -> {
                if (!current(request)) return;
                if (handshook) {
                    confirmed = true;
                    retries = 0;
                    new ConnectionHistory(this).record(request.entry,ConnectionHistory.network(this),true);
                    SavedConnection.remember(this,request.entry,request.config);
                    publish(new Snapshot(State.UP,request.entry,startedAt,true,"",request.token));
                    updateNotification("Connected · " + request.entry.name);
                } else {
                    long elapsed = SystemClock.elapsedRealtime() - startedAt;
                    if (elapsed >= 20_000 && snapshot.message.startsWith("Tunnel ready")) {
                        publish(new Snapshot(State.UP,request.entry,startedAt,false,
                                "No peer handshake yet. Check the endpoint if traffic fails.",request.token));
                    }
                    MAIN.postDelayed(poll,elapsed < 30_000 ? 3000 : 15_000);
                }
            });
        });
    }

    private void failed(Request request,String reason) {
        if (!current(request)) return;
        replacing = false;
        MAIN.removeCallbacks(poll);
        SavedConnection saved = SavedConnection.read(this);
        if (new VpnOptions(this).autoReconnect() && saved != null && saved.entry.id.equals(request.entry.id)
                && retries < 3 && prepare(this) == null) {
            retryScheduled = true;
            publish(new Snapshot(State.STARTING,request.entry,0,false,"Retrying… " + reason,request.token));
            updateNotification("Retrying WireGuard…");
            MAIN.removeCallbacks(resume);
            MAIN.postDelayed(resume,1000L << ++retries);
        } else {
            retryScheduled = false;
            publish(new Snapshot(State.ERROR,request.entry,0,false,reason,request.token));
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
        }
    }

    private void reconnect() {
        Request request = active;
        if (request == null || !current(request) || prepare(this) != null) return;
        if (!new VpnOptions(this).autoReconnect()) {
            failed(request,"The WireGuard tunnel stopped.");
            return;
        }
        replacing = true;
        MAIN.removeCallbacks(poll);
        startWireGuard(request);
    }

    private void observeNetwork() {
        if (callback != null) return;
        networkManager = getSystemService(ConnectivityManager.class);
        if (networkManager == null) return;
        underlying = usableNetwork();
        ConnectivityManager.NetworkCallback events = new ConnectivityManager.NetworkCallback() {
            @Override public void onAvailable(Network network) { queueNetworkChange(); }
            @Override public void onLost(Network network) { queueNetworkChange(); }
            @Override public void onCapabilitiesChanged(Network n,NetworkCapabilities c) { queueNetworkChange(); }
        };
        try {
            networkManager.registerNetworkCallback(new NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN).build(),events);
            callback = events;
        } catch (RuntimeException ignored) {}
    }

    private void queueNetworkChange() {
        MAIN.removeCallbacks(networkChange);
        MAIN.postDelayed(networkChange,1500);
    }

    private Network usableNetwork() {
        if (networkManager == null) return null;
        Network best = null;
        int score = -1;
        for (Network network : networkManager.getAllNetworks()) {
            NetworkCapabilities caps = networkManager.getNetworkCapabilities(network);
            if (caps == null || caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
                    || !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) continue;
            int rank = caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ? 3
                    : caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ? 2 : 1;
            if (rank > score) {
                best = network;
                score = rank;
            }
        }
        return best;
    }

    private void checkNetworkChange() {
        if (destroyed) return;
        Network current = usableNetwork();
        if (!Objects.equals(current,underlying)) {
            underlying = current;
            if (current != null && retryScheduled) {
                MAIN.removeCallbacks(resume);
                reconnect();
            }
            // An active WireGuard tunnel roams across networks without tearing down Android's VPN.
        }
    }

    @Override public void onRevoke() {
        SavedConnection.clear(this);
        ++requestCount;
        publish(new Snapshot(State.ERROR,null,0,false,"Android revoked VPN access.",requestCount));
        stopSelf();
        super.onRevoke();
    }

    @Override public void onDestroy() {
        destroyed = true;
        MAIN.removeCallbacks(poll);
        MAIN.removeCallbacks(resume);
        MAIN.removeCallbacks(networkChange);
        if (callback != null) networkManager.unregisterNetworkCallback(callback);
        worker.shutdownNow();
        if (active != null && snapshot.state != State.ERROR && requestCount == active.token) {
            publish(new Snapshot(State.ERROR,active.entry,0,false,"The WireGuard tunnel stopped.",active.token));
        }
        stopForeground(STOP_FOREGROUND_REMOVE);
        super.onDestroy();
    }

    private Notification notification(String message) {
        NotificationManager manager = getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel(CHANNEL,"WireGuard connection",NotificationManager.IMPORTANCE_LOW));
        Intent open = new Intent(this,PaperlineActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        Intent stop = new Intent(this,SetuVpnService.class).setAction(STOP);
        int bridge = getResources().getIdentifier("ic_stat_vpn","drawable","de.blinkt.openvpn");
        if (bridge == 0) bridge = android.R.drawable.ic_lock_lock;
        return new Notification.Builder(this,CHANNEL).setSmallIcon(bridge).setContentTitle("Setu VPN")
                .setContentText(message).setOngoing(true).setOnlyAlertOnce(true)
                .setContentIntent(PendingIntent.getActivity(this,0,open,PendingIntent.FLAG_IMMUTABLE))
                .addAction(new Notification.Action.Builder(null,"Disconnect",
                        PendingIntent.getService(this,0,stop,PendingIntent.FLAG_IMMUTABLE)).build())
                .build();
    }

    private void updateNotification(String message) {
        getSystemService(NotificationManager.class).notify(NOTIFICATION,notification(message));
    }
}
