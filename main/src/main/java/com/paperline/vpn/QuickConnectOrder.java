package com.paperline.vpn;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

final class QuickConnectOrder {
    private static final int PROBE_COUNT = 8;
    private static final int PROBE_TIMEOUT_MS = 900;
    private static final int NO_RESPONSE = Integer.MAX_VALUE;

    static List<VpnDirectory.Relay> rank(List<VpnDirectory.Relay> source,Set<String> tried,
                                         boolean global,String previous,long seed) {
        List<VpnDirectory.Relay> candidates = new ArrayList<>(source.size());
        for (VpnDirectory.Relay relay : source) {
            if (!tried.contains(relay.ip)) candidates.add(relay);
        }
        return RustCore.order(candidates,false,global,previous,seed);
    }

    static void measureFirstCountry(List<VpnDirectory.Relay> order) throws InterruptedException {
        if (order.isEmpty()) return;
        String first = order.get(0).country;
        List<VpnDirectory.Relay> local = new ArrayList<>();
        for (VpnDirectory.Relay relay : order) if (first.equals(relay.country)) local.add(relay);
        measureLatency(local);
        Iterator<VpnDirectory.Relay> measured = local.iterator();
        for (int i = 0; i < order.size(); i++) {
            if (first.equals(order.get(i).country)) order.set(i,measured.next());
        }
    }

    static void measureLatency(List<VpnDirectory.Relay> ranked) throws InterruptedException {
        List<VpnDirectory.Relay> checked = new ArrayList<>();
        List<Callable<Integer>> probes = new ArrayList<>();
        for (int i = 0; i < Math.min(PROBE_COUNT, ranked.size()); i++) {
            VpnDirectory.Relay relay = ranked.get(i);
            if (relay.probePort <= 0) continue;
            checked.add(relay);
            probes.add(() -> tcpLatency(relay));
        }
        if (probes.isEmpty()) return;

        Map<VpnDirectory.Relay,Integer> delays = new HashMap<>();
        ExecutorService network = Executors.newFixedThreadPool(probes.size());
        try {
            List<Future<Integer>> results = network.invokeAll(probes, PROBE_TIMEOUT_MS + 150, TimeUnit.MILLISECONDS);
            for (int i = 0; i < results.size(); i++) {
                Future<Integer> result = results.get(i);
                if (!result.isCancelled()) {
                    try { delays.put(checked.get(i), result.get()); }
                    catch (java.util.concurrent.ExecutionException ignored) {
                        delays.put(checked.get(i), NO_RESPONSE);
                    }
                } else delays.put(checked.get(i), NO_RESPONSE);
            }
        } finally {
            network.shutdownNow();
        }

        // Keep the directory order when there was no comparable device measurement.
        ranked.sort(Comparator.comparingInt(relay -> delays.getOrDefault(relay, NO_RESPONSE - 1)));
    }

    private static int tcpLatency(VpnDirectory.Relay relay) {
        long start = System.nanoTime();
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(relay.ip,relay.probePort),PROBE_TIMEOUT_MS);
            return (int) TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        } catch (IOException error) {
            return NO_RESPONSE;
        }
    }
}
