package com.paperline.vpn;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Pattern;

final class LocationSearch {
    private static final Pattern NON_LETTERS = Pattern.compile("[^\\p{L}\\p{N}]");
    private static final String[] NO_ALIASES = {};

    private LocationSearch() {}

    static String normalize(String value) {
        return NON_LETTERS.matcher(Normalizer.normalize(value.toLowerCase(Locale.ROOT),Normalizer.Form.NFD))
                .replaceAll("");
    }

    static int match(String code,String name,String term) {
        if (term.isEmpty()) return 1;
        String normalizedName = normalize(name);
        if (term.equals(code.toLowerCase(Locale.ROOT)) || term.equals(normalizedName)) return 3;
        int score = normalizedName.startsWith(term) ? 2 : normalizedName.contains(term) ? 1 : 0;
        for (String alias : aliases(code)) {
            if (term.equals(alias)) return 3;
            if (term.length() >= 3 && alias.startsWith(term)) score = 2;
        }
        return score;
    }

    private static String[] aliases(String code) {
        switch (code) {
            case "US": return new String[]{"unitedstates","unitedstatesofamerica","america"};
            case "GB": return new String[]{"uk","britain","greatbritain","unitedkingdom","england"};
            case "KR": return new String[]{"korea","republicofkorea","korearepublic"};
            case "AE": return new String[]{"uae","emirates"};
            case "MM": return new String[]{"burma"};
            case "CZ": return new String[]{"czechrepublic","czechia"};
            case "IN": return new String[]{"bharat"};
            case "LK": return new String[]{"shrilanka","ceylon"};
            case "NL": return new String[]{"holland"};
            case "VN": return new String[]{"vietnam"};
            default: return NO_ALIASES;
        }
    }
}
