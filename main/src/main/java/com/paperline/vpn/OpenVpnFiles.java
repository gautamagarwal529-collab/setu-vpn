package com.paperline.vpn;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

final class OpenVpnFiles {
    private static final int MAX_ARCHIVE = 2 * 1024 * 1024;
    private static final int MAX_ENTRIES = 32;
    private static final Pattern WIREGUARD_SECTION =
            Pattern.compile("(?im)^[\\t ]*\\[(?:Interface|Peer)\\][\\t ]*(?:#.*)?$");

    static final class Profile {
        final String name, config;
        final boolean wireGuard;

        Profile(String name,String config,boolean wireGuard) {
            this.name = name;
            this.config = config;
            this.wireGuard = wireGuard;
        }
    }

    static List<Profile> read(InputStream stream,String filename) throws IOException {
        byte[] file = VpnDirectory.read(stream,MAX_ARCHIVE);
        boolean zip = file.length >= 4 && file[0] == 'P' && file[1] == 'K'
                && ((file[2] == 3 && file[3] == 4) || (file[2] == 5 && file[3] == 6));
        List<Profile> profiles = new ArrayList<>();
        if (!zip) {
            profiles.add(profile(file,filename));
            return profiles;
        }

        int entries = 0;
        int remaining = MAX_ARCHIVE;
        try (ZipInputStream archive = new ZipInputStream(new ByteArrayInputStream(file))) {
            ZipEntry entry;
            while ((entry = archive.getNextEntry()) != null) {
                if (++entries > MAX_ENTRIES) throw new IOException("The ZIP contains too many files.");
                byte[] bytes = VpnDirectory.read(archive,Math.min(VpnDirectory.MAX_PROFILE,remaining));
                remaining -= bytes.length;
                if (!entry.isDirectory() && entry.getName().toLowerCase(Locale.ROOT).matches(".*\\.(ovpn|conf)$"))
                    profiles.add(profile(bytes,entry.getName()));
            }
        }
        if (profiles.isEmpty()) throw new IOException("The ZIP contains no .ovpn or WireGuard .conf profiles.");
        return profiles;
    }

    private static Profile profile(byte[] bytes,String filename) throws IOException {
        if (bytes.length > VpnDirectory.MAX_PROFILE) throw new IOException("VPN profile is too large.");
        int slash = Math.max(filename.lastIndexOf('/'),filename.lastIndexOf('\\'));
        String name = filename.substring(slash + 1).replaceFirst("(?i)\\.(ovpn|conf)$","")
                .replaceAll("[\\p{Cntrl}]","").trim();
        if (name.isEmpty()) name = "Imported profile";
        if (name.length() > 75) name = name.substring(0,75);
        String content = VpnDirectory.utf8(bytes);
        if (content.startsWith("\uFEFF")) content = content.substring(1);
        if (WIREGUARD_SECTION.matcher(content).find()) {
            try { return new Profile(name,WireGuardProfiles.parse(content).toWgQuickString(),true); }
            catch (IOException error) { throw error; }
            catch (Exception error) { throw new IOException("Invalid WireGuard profile. Check the interface, private key, and peer settings."); }
        }
        return new Profile(name,RustCore.cleanProfile(content,null,false),false);
    }
}
