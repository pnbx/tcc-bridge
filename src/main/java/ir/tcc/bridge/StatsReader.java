package ir.tcc.bridge;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;

import java.io.File;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads cumulative player statistics through PlaceholderAPI (the Statistics
 * expansion reads each player's own stats file, so the numbers cover everything
 * they have done on this server) and hands them to the website.
 *
 * PlaceholderAPI is optional: the class is resolved reflectively, so the plugin
 * still works on a server without it.
 */
final class StatsReader {

    private static final String[] KILLS = {"%statistic_player_kills%"};
    private static final String[] DEATHS = {"%statistic_deaths%"};
    private static final String[] PLAY_TIME = {"%statistic_time_played%"};
    private static final String[] MOB_KILLS = {"%statistic_mob_kills%"};
    private static final Pattern TIME = Pattern.compile("(?:(\\d+)d)?\\s*(?:(\\d+)h)?\\s*(?:(\\d+)m)?\\s*(?:(\\d+)s)?");

    private final Path root;
    private final Method setPlaceholders;
    private final Object papiInstance;

    StatsReader(Path serverRoot) {
        this.root = serverRoot;
        Method method = null;
        Object instance = null;
        try {
            Class<?> papi = Class.forName("me.clip.placeholderapi.PlaceholderAPI");
            method = papi.getMethod("setPlaceholders", OfflinePlayer.class, String.class);
            instance = papi.getField("INSTANCE").get(null); // may not exist on older builds
        } catch (Exception ignored) {
            // PlaceholderAPI absent or API shape differs → stats simply stay empty
        }
        this.setPlaceholders = method;
        this.papiInstance = instance;
    }

    boolean available() {
        return setPlaceholders != null;
    }

    /** { players: [{uuid, username, kills, deaths, playtimeMinutes, mobKills}] } */
    Map<String, Object> readAll(int limit) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (!available()) return Map.of("players", out, "source", "unavailable");

        for (Map<String, String> entry : usercache(limit)) {
            UUID uuid;
            try {
                uuid = UUID.fromString(entry.get("uuid"));
            } catch (Exception e) {
                continue;
            }
            OfflinePlayer player = Bukkit.getOfflinePlayer(uuid);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("uuid", uuid.toString().toLowerCase(Locale.ROOT));
            row.put("username", entry.get("name"));
            row.put("kills", resolveInt(player, KILLS));
            row.put("deaths", resolveInt(player, DEATHS));
            row.put("playtimeMinutes", resolveMinutes(player));
            row.put("mobKills", resolveInt(player, MOB_KILLS));
            row.put("lastSeen", player.getLastSeen() > 0 ? new java.util.Date(player.getLastSeen()).toInstant().toString() : null);
            out.add(row);
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("players", out);
        payload.put("source", "placeholderapi");
        return payload;
    }

    private String resolve(OfflinePlayer player, String placeholder) {
        try {
            return setPlaceholders.invoke(papiInstance, player, placeholder);
        } catch (Exception e) {
            return null;
        }
    }

    private Integer resolveInt(OfflinePlayer player, String[] placeholders) {
        String raw = resolve(player, placeholders[0]);
        if (raw == null) return null;
        String digits = raw.replaceAll("[^0-9-]", "");
        if (digits.isEmpty() || digits.equals("-")) return null;
        try {
            return Math.max(0, Integer.parseInt(digits));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** "5h 24m 29s" / "1d 2h" → minutes */
    private Integer resolveMinutes(OfflinePlayer player) {
        String raw = resolve(player, PLAY_TIME[0]);
        if (raw == null || raw.isBlank()) return null;
        Matcher m = TIME.matcher(raw);
        if (!m.find()) return null;
        int days = num(m.group(1));
        int hours = num(m.group(2));
        int minutes = num(m.group(3));
        int seconds = num(m.group(4));
        int total = days * 1440 + hours * 60 + minutes + (seconds >= 30 ? 1 : 0);
        return total;
    }

    private int num(String value) {
        if (value == null || value.isEmpty()) return 0;
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** Most recently active accounts first, capped so a big server stays cheap. */
    private List<Map<String, String>> usercache(int limit) {
        List<Map<String, String>> out = new ArrayList<>();
        Path cache = root.resolve("usercache.json");
        if (!Files.isRegularFile(cache)) return out;
        try {
            String text = Files.readString(cache, StandardCharsets.UTF_8);
            List<Map<String, String>> rows = new ArrayList<>();
            for (Object raw : Json.array(Json.read(text))) {
                Map<String, Object> entry = Json.object(raw);
                String uuid = Json.string(entry.get("uuid"));
                String name = Json.string(entry.get("name"));
                if (uuid == null || name == null) continue;
                Map<String, String> row = new LinkedHashMap<>();
                row.put("uuid", uuid.toLowerCase(Locale.ROOT));
                row.put("name", name);
                row.put("expires", Json.string(entry.get("expiresOn")));
                rows.add(row);
            }
            rows.sort(Comparator.comparing((Map<String, String> r) -> r.getOrDefault("expires", "")).reversed());
            out.addAll(rows.subList(0, Math.min(limit, rows.size())));
        } catch (Exception ignored) {
        }
        return out;
    }

    File cacheFile() {
        return root.resolve("usercache.json").toFile();
    }
}
