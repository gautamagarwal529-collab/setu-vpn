package com.paperline.vpn;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

final class QuickConnectOrder {
    private static final String INDIA = "IN";
    private static final int INDIA_FIRST_ODDS = 25;
    private static final int PROBE_COUNT = 8;
    private static final int PROBE_TIMEOUT_MS = 900;
    private static final int NO_RESPONSE = Integer.MAX_VALUE;

    static List<VpnDirectory.Relay> rank(List<VpnDirectory.Relay> source, Set<String> tried) {
        long recent = System.currentTimeMillis() - 18 * VpnDirectory.HOUR;
        List<VpnDirectory.Relay> ranked = new ArrayList<>();
        for (VpnDirectory.Relay relay : source) if (!tried.contains(relay.ip)) ranked.add(relay);

        ranked.sort(Comparator.<VpnDirectory.Relay>comparingInt(relay ->
                        "reachable".equals(relay.tcp) && relay.tcpChecked > recent ? 0 : relay.current ? 1 : 2)
                .thenComparingInt(relay -> relay.ping > 0 ? relay.ping : Integer.MAX_VALUE)
                .thenComparing(Comparator.comparingLong((VpnDirectory.Relay relay) -> relay.speed).reversed())
                .thenComparing(Comparator.comparingLong((VpnDirectory.Relay relay) -> relay.lastSeen).reversed()));

        Set<String> unique = new HashSet<>();
        ranked.removeIf(relay -> !unique.add(relay.ip));
        return ranked;
    }

    static List<VpnDirectory.Relay> rotateCountries(List<VpnDirectory.Relay> ranked,String previous,Random random) {
        Map<String,ArrayDeque<VpnDirectory.Relay>> byCountry = new LinkedHashMap<>();
        for (VpnDirectory.Relay relay : ranked) {
            byCountry.computeIfAbsent(relay.country,code -> new ArrayDeque<>()).addLast(relay);
        }
        ArrayDeque<VpnDirectory.Relay> india = byCountry.remove(INDIA);
        if (byCountry.isEmpty()) return new ArrayList<>(ranked);

        List<ArrayDeque<VpnDirectory.Relay>> shuffled = new ArrayList<>(byCountry.values());
        Collections.shuffle(shuffled,random);
        if (shuffled.size() > 1 && shuffled.get(0).peekFirst().country.equals(previous)) {
            Collections.swap(shuffled,0,1 + random.nextInt(shuffled.size() - 1));
        }

        List<VpnDirectory.Relay> order = new ArrayList<>(ranked.size());
        if (india != null && !INDIA.equals(previous) && random.nextInt(INDIA_FIRST_ODDS) == 0) {
            order.add(india.removeFirst());
        }
        // Give each international location a turn before trying its next relay.
        ArrayDeque<ArrayDeque<VpnDirectory.Relay>> countries = new ArrayDeque<>(shuffled);
        while (!countries.isEmpty()) {
            ArrayDeque<VpnDirectory.Relay> relays = countries.removeFirst();
            order.add(relays.removeFirst());
            if (!relays.isEmpty()) countries.addLast(relays);
        }
        if (india != null) order.addAll(india);
        return order;
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
