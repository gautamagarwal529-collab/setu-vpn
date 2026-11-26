package com.paperline.vpn;

import com.wireguard.config.Config;
import com.wireguard.config.Interface;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.util.HashSet;
import java.util.Set;

final class WireGuardProfiles {
    private WireGuardProfiles() {}

    static Config parse(String text) throws Exception {
        if (text.indexOf(0) >= 0) throw new IOException("Invalid WireGuard profile.");
        Config profile = Config.parse(new BufferedReader(new StringReader(text)));
        if (profile.getInterface().getAddresses().isEmpty())
            throw new IOException("The WireGuard profile needs an interface address.");
        if (profile.getPeers().isEmpty() || profile.getPeers().stream().noneMatch(peer -> peer.getEndpoint().isPresent()))
            throw new IOException("The WireGuard profile needs a peer endpoint.");
        return profile;
    }

    static Config apply(Config original,VpnOptions options) throws Exception {
        Interface source = original.getInterface();
        Interface.Builder iface = new Interface.Builder().setKeyPair(source.getKeyPair())
                .addAddresses(source.getAddresses()).addDnsServers(source.getDnsServers())
                .addDnsSearchDomains(source.getDnsSearchDomains());
        if (source.getListenPort().isPresent()) iface.setListenPort(source.getListenPort().get());
        if (source.getMtu().isPresent()) iface.setMtu(source.getMtu().get());
        Set<String> excluded = options.excluded();
        if (source.getIncludedApplications().isEmpty()) {
            excluded.addAll(source.getExcludedApplications());
            iface.excludeApplications(options.installed(excluded));
        } else {
            Set<String> allowed = new HashSet<>(source.getIncludedApplications());
            allowed.removeAll(excluded);
            allowed = options.installed(allowed);
            if (allowed.isEmpty()) throw new IOException("All apps allowed by this WireGuard profile are excluded or unavailable.");
            iface.includeApplications(allowed);
        }
        return new Config.Builder().setInterface(iface.build()).addPeers(original.getPeers()).build();
    }
}
