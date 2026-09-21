package ir.tcc.bridge;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads the in-game tier system so the website can mirror it without anyone
 * touching the site.
 *
 * Source of truth is Skript's variable storage: the script `givetier.sk`
 * ("TCC TIER SYSTEM") writes `tier.&lt;mode&gt;::&lt;uuid&gt;` for every /settier,
 * /demote and /demoteall. We also read usercache.json so the site can show real
 * player names instead of raw UUIDs.
 */
final class TierReader {

    private static final Pattern TIER_KEY = Pattern.compile("^tier\\.([a-z_]+)::([0-9a-fA-F-]{8,64})$");
    private static final Pattern SAFE_TIER = Pattern.compile("^[a-zA-Z0-9_]{1,12}$");

    private final Path root;

    TierReader(Path serverRoot) {
        this.root = serverRoot;
    }

    /** { players: [{uuid,name}], tiers: [{uuid,mode,tier}], source: "skript" } */
    Map<String, Object> readAll() {
        List<Map<String, Object>> tiers = new ArrayList<>();
        Path variables = root.resolve("plugins").resolve("Skript").resolve("variables.csv");

        if (Files.isRegularFile(variables)) {
            try {
                for (String raw : Files.readAllLines(variables, StandardCharsets.UTF_8)) {
                    if (raw.isBlank() || raw.startsWith("#")) continue;
                    int firstComma = raw.indexOf(',');
                    if (firstComma <= 0) continue;
                    String key = raw.substring(0, firstComma).trim();
                    Matcher m = TIER_KEY.matcher(key);
                    if (!m.matches()) continue;
                    String mode = m.group(1).toLowerCase(Locale.ROOT);
                    String uuid = m.group(2).toLowerCase(Locale.ROOT);
                    // value is everything after "key,type," — Skript writes ", text, ht1"
                    int secondComma = raw.indexOf(',', firstComma + 1);
                    if (secondComma < 0) continue;
                    String value = raw.substring(secondComma + 1).trim();
                    if (!SAFE_TIER.matcher(value).matches()) continue;
                    Map<String, Object> entry = new LinkedHashMap<>();
                    entry.put("uuid", uuid);
                    entry.put("mode", mode);
                    entry.put("tier", value.toLowerCase(Locale.ROOT));
                    tiers.add(entry);
                }
            } catch (Exception ignored) {
                // unreadable file must never break the bridge
            }
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("players", readUsercache());
        payload.put("tiers", tiers);
        payload.put("source", "skript");
        return payload;
    }

    private List<Map<String, Object>> readUsercache() {
        List<Map<String, Object>> out = new ArrayList<>();
        Path cache = root.resolve("usercache.json");
        if (!Files.isRegularFile(cache)) return out;
        try {
            String text = Files.readString(cache, StandardCharsets.UTF_8);
            for (Object raw : Json.array(Json.read(text))) {
                Map<String, Object> entry = Json.object(raw);
                String uuid = Json.string(entry.get("uuid"));
                String name = Json.string(entry.get("name"));
                if (uuid == null || name == null) continue;
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("uuid", uuid.toLowerCase(Locale.ROOT));
                row.put("name", name);
                out.add(row);
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    File variablesFile() {
        return root.resolve("plugins").resolve("Skript").resolve("variables.csv").toFile();
    }
}
