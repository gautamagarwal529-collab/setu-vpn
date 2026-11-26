package com.paperline.vpn;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

final class OpenVpnFiles {
    private static final int MAX_ARCHIVE = 2 * 1024 * 1024;
    private static final int MAX_ENTRIES = 32;

    static final class Profile {
        final String name, config;

        Profile(String name,String config) {
            this.name = name;
            this.config = config;
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
                if (!entry.isDirectory() && entry.getName().toLowerCase(Locale.ROOT).endsWith(".ovpn"))
                    profiles.add(profile(bytes,entry.getName()));
            }
        }
        if (profiles.isEmpty()) throw new IOException("The ZIP contains no OpenVPN profiles.");
        return profiles;
    }

    private static Profile profile(byte[] bytes,String filename) throws IOException {
        if (bytes.length > VpnDirectory.MAX_PROFILE) throw new IOException("OpenVPN profile is too large.");
        int slash = Math.max(filename.lastIndexOf('/'),filename.lastIndexOf('\\'));
        String name = filename.substring(slash + 1).replaceFirst("(?i)\\.ovpn$","")
                .replaceAll("[\\p{Cntrl}]","").trim();
        if (name.isEmpty()) name = "Imported profile";
        if (name.length() > 75) name = name.substring(0,75);
        return new Profile(name,RustCore.cleanProfile(VpnDirectory.utf8(bytes),null,false));
    }
}
