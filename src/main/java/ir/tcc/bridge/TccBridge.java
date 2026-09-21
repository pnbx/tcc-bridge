package ir.tcc.bridge;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryUsage;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * TccBridge — the game server's half of the website integration.
 *
 * The site and the game host cannot always reach each other (the Iranian game
 * host drops foreign traffic), so this plugin drives the connection outward and
 * doubles as the agent for everything the hosting panel used to provide:
 *
 *   push    → live status (players, MOTD, version, TPS, MSPT, RAM, disk)
 *   logs    → console lines tailed from logs/latest.log
 *   poll    → queued work: console commands (rank grants), file operations, power
 *   link    → /site connect &lt;code&gt; proves account ownership in game
 *
 * Endpoints: /api/mc/ingest, /api/mc/logs, /api/mc/poll, /api/mc/ack and
 * /api/account/link/confirm — all authenticated with the shared token header.
 */
public final class TccBridge extends JavaPlugin {

    private HttpClient http;
    private String siteUrl;
    private String token;
    private int pushSeconds;
    private int pollSeconds;
    private int logSeconds;
    private int tierSeconds;
    private int statsSeconds;

    private BukkitTask pushTask;
    private BukkitTask pollTask;
    private BukkitTask logTask;
    private BukkitTask tierTask;
    private BukkitTask statsTask;

    private final AtomicBoolean pushInFlight = new AtomicBoolean(false);
    private final AtomicBoolean pollInFlight = new AtomicBoolean(false);
    private final AtomicBoolean logInFlight = new AtomicBoolean(false);
    private final AtomicBoolean tierInFlight = new AtomicBoolean(false);
    private final AtomicBoolean statsInFlight = new AtomicBoolean(false);

    private Fs fs;
    private LogTailer tailer;
    private TierReader tierReader;
    private StatsReader statsReader;
    private final long startedAt = System.currentTimeMillis();

    private volatile long lastPushAt = 0L;
    private volatile String lastPushResult = "هرگز";

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadSettings();

        http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .version(HttpClient.Version.HTTP_1_1)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();

        // the plugin lives in <server>/plugins/TccBridge, so the server root is two levels up
        File serverRoot = getDataFolder().getParentFile() == null
                ? new File(".")
                : getDataFolder().getParentFile().getParentFile();
        File root = serverRoot == null ? new File(".") : serverRoot;
        fs = new Fs(root);
        tailer = new LogTailer(root);
        tierReader = new TierReader(root.toPath());
        statsReader = new StatsReader(root.toPath());

        if (token.isEmpty()) {
            getLogger().warning("token is empty — put the website's MC_INGEST_TOKEN in plugins/TccBridge/config.yml, then /site reload");
        }

        startTasks();
        getLogger().info("TccBridge enabled → " + siteUrl
                + " (status " + pushSeconds + "s, work " + pollSeconds + "s, console " + logSeconds + "s, root " + root.getAbsolutePath() + ")");
    }

    @Override
    public void onDisable() {
        stopTasks();
        getLogger().info("TccBridge disabled.");
    }

    private void loadSettings() {
        siteUrl = getConfig().getString("site-url", "https://site.tccmc.ir").replaceAll("/+$", "");
        token = getConfig().getString("token", "").trim();
        pushSeconds = Math.max(5, getConfig().getInt("push-interval-seconds", 20));
        pollSeconds = Math.max(5, getConfig().getInt("poll-interval-seconds", 10));
        logSeconds = Math.max(3, getConfig().getInt("console-push-seconds", 5));
        tierSeconds = Math.max(20, getConfig().getInt("tier-push-seconds", 60));
        statsSeconds = Math.max(120, getConfig().getInt("stats-push-seconds", 900));
    }

    private void startTasks() {
        stopTasks();
        pushTask = Bukkit.getScheduler().runTaskTimerAsynchronously(this, this::pushStatus, 60L, pushSeconds * 20L);
        pollTask = Bukkit.getScheduler().runTaskTimerAsynchronously(this, this::pollWork, 120L, pollSeconds * 20L);
        logTask = Bukkit.getScheduler().runTaskTimerAsynchronously(this, this::pushConsole, 100L, logSeconds * 20L);
        tierTask = Bukkit.getScheduler().runTaskTimerAsynchronously(this, this::pushTiers, 200L, tierSeconds * 20L);
        statsTask = Bukkit.getScheduler().runTaskTimerAsynchronously(this, this::pushStats, 600L, statsSeconds * 20L);
    }

    private void stopTasks() {
        if (pushTask != null) pushTask.cancel();
        if (pollTask != null) pollTask.cancel();
        if (logTask != null) logTask.cancel();
        if (tierTask != null) tierTask.cancel();
        if (statsTask != null) statsTask.cancel();
    }

    // ── status push ──────────────────────────────────────────────────────────

    private void pushStatus() {
        if (token.isEmpty() || !pushInFlight.compareAndSet(false, true)) return;
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("playersOnline", Bukkit.getOnlinePlayers().size());
            body.put("playersMax", Bukkit.getMaxPlayers());
            body.put("version", Bukkit.getMinecraftVersion());
            body.put("motd", List.of(ChatColor.stripColor(Bukkit.getMotd() == null ? "" : Bukkit.getMotd())));
            double[] tps = Bukkit.getServer().getTPS();
            if (tps.length > 0) body.put("tps", round2(tps[0]));
            body.put("mspt", round2(Bukkit.getServer().getAverageTickTime()));

            MemoryUsage heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
            body.put("memoryUsedMb", round2(heap.getUsed() / 1048576.0));
            body.put("memoryMaxMb", round2(heap.getMax() / 1048576.0));
            body.put("diskUsedGb", diskUsedGb());
            body.put("uptimeSec", (System.currentTimeMillis() - startedAt) / 1000);
            body.put("source", "plugin");

            Response res = post("/api/mc/ingest", Json.write(body));
            if (res.status == 200) {
                lastPushAt = System.currentTimeMillis();
                lastPushResult = "موفق";
            } else {
                lastPushResult = "خطای " + res.status;
                getLogger().warning("status push failed: HTTP " + res.status + " " + trim(res.body));
            }
        } catch (Exception e) {
            lastPushResult = "خطا — " + e.getClass().getSimpleName();
        } finally {
            pushInFlight.set(false);
        }
    }


    private volatile double cachedDiskGb = -1;
    private volatile long cachedDiskAt = 0;

    /** Bytes actually used by this server's own directory (10-minute cache). */
    private double diskUsedGb() {
        long now = System.currentTimeMillis();
        if (cachedDiskGb >= 0 && now - cachedDiskAt < 600_000) return cachedDiskGb;
        long total = 0;
        int visited = 0;
        try (var walk = java.nio.file.Files.walk(fs.root())) {
            for (var it = walk.iterator(); it.hasNext() && visited < 300000; ) {
                var path = it.next();
                visited++;
                try {
                    if (java.nio.file.Files.isRegularFile(path)) total += java.nio.file.Files.size(path);
                } catch (Exception ignored) {
                }
            }
        } catch (Exception ignored) {
        }
        cachedDiskGb = round2(total / 1073741824.0);
        cachedDiskAt = now;
        return cachedDiskGb;
    }

    // ── console feed ─────────────────────────────────────────────────────────

    private void pushConsole() {
        if (token.isEmpty() || !logInFlight.compareAndSet(false, true)) return;
        try {
            List<Map<String, Object>> lines = tailer.poll(200);
            if (lines.isEmpty()) return;
            post("/api/mc/logs", Json.write(Map.of("lines", lines)));
        } catch (Exception e) {
            getLogger().warning("console push error: " + e.getMessage());
        } finally {
            logInFlight.set(false);
        }
    }

    /** Mirrors the in-game tier system to the site (Skript variables are the source). */
    private void pushTiers() {
        if (token.isEmpty() || !tierInFlight.compareAndSet(false, true)) return;
        try {
            Map<String, Object> payload = tierReader.readAll();
            List<?> tiers = (List<?>) payload.get("tiers");
            if (tiers == null || tiers.isEmpty()) return; // nothing assigned yet
            post("/api/mc/tiers", Json.write(payload));
        } catch (Exception e) {
            getLogger().warning("tier sync error: " + e.getMessage());
        } finally {
            tierInFlight.set(false);
        }
    }
    /**
     * Pushes cumulative player statistics read through PlaceholderAPI (the
     * Statistics expansion reads each player's own stats file, so the numbers
     * cover everything they have done on this server).
     */
    private void pushStats() {
        if (token.isEmpty() || !statsInFlight.compareAndSet(false, true)) return;
        try {
            if (!statsReader.available()) return; // PlaceholderAPI not installed
            int limit = Math.max(10, getConfig().getInt("stats-players", 120));
            Map<String, Object> payload = statsReader.readAll(limit);
            List<?> players = (List<?>) payload.get("players");
            if (players == null || players.isEmpty()) return;
            Response res = post("/api/mc/stats", Json.write(payload));
            if (res.status == 200) {
                lastPushResult = "موفق";
                getLogger().info("pushed stats for " + players.size() + " players");
            } else {
                getLogger().warning("stats push failed: HTTP " + res.status + " " + trim(res.body));
            }
        } catch (Exception e) {
            getLogger().warning("stats push error: " + e.getMessage());
        } finally {
            statsInFlight.set(false);
        }
    }
    // ── work queue ───────────────────────────────────────────────────────────

    private void pollWork() {
        if (token.isEmpty() || !pollInFlight.compareAndSet(false, true)) return;
        try {
            Response res = post("/api/mc/poll", "{}");
            if (res.status != 200) {
                if (res.status != 401) getLogger().warning("poll failed: HTTP " + res.status);
                return;
            }
            Map<String, Object> root = Json.object(Json.read(res.body));
            List<Object> jobs = Json.array(Json.object(root.get("data")).get("commands"));
            if (jobs.isEmpty()) return;

            List<Map<String, Object>> results = new ArrayList<>();
            for (Object raw : jobs) {
                Map<String, Object> job = Json.object(raw);
                String id = Json.string(job.get("id"));
                String kind = Json.string(job.get("kind"));
                if (id == null) continue;
                if (kind == null || kind.isEmpty()) kind = "command";
                Map<String, Object> result = handleJob(id, kind, job);
                if (result != null) results.add(result); // power acks itself before acting
            }
            if (!results.isEmpty()) ack(results);
        } catch (Exception e) {
            getLogger().warning("poll error: " + e.getMessage());
        } finally {
            pollInFlight.set(false);
        }
    }

    private Map<String, Object> handleJob(String id, String kind, Map<String, Object> job) {
        Object payloadRaw = job.get("payload");
        Map<String, Object> payload = payloadRaw == null ? Map.of() : Json.object(payloadRaw);

        switch (kind) {
            case "command":
                return runConsoleCommand(id, Json.string(job.get("command")));
            case "file.list":
                return jobResult(id, () -> fs.list(Json.string(payload.get("dir"))));
            case "file.read":
                return jobResult(id, () -> fs.read(Json.string(payload.get("path")), intOf(payload.get("limitBytes"), 512 * 1024)));
            case "file.write":
                return jobResult(id, () -> {
                    fs.write(Json.string(payload.get("path")), Json.string(payload.get("content")), boolOf(payload.get("exclusive")));
                    return Map.of("ok", true);
                });
            case "file.delete":
                return jobResult(id, () -> {
                    fs.delete(Json.string(payload.get("path")));
                    return Map.of("ok", true);
                });
            case "file.mkdir":
                return jobResult(id, () -> {
                    fs.mkdir(Json.string(payload.get("path")));
                    return Map.of("ok", true);
                });
            case "file.rename":
                return jobResult(id, () -> {
                    fs.rename(Json.string(payload.get("from")), Json.string(payload.get("to")));
                    return Map.of("ok", true);
                });
            case "stats.read":
                return jobResult(id, () -> statsReader.readAll(Math.max(10, getConfig().getInt("stats-players", 120))));
            case "tiers.read":
                return jobResult(id, () -> tierReader.readAll());
            case "file.search":
                return jobResult(id, () -> fs.search(Json.string(payload.get("dir")), Json.string(payload.get("pattern"))));
            case "power.restart":
                return powerAction(id, "restart", boolOf(payload.get("force")));
            case "power.stop":
                return powerAction(id, "stop", boolOf(payload.get("force")));
            default:
                return simpleResult(id, false, "unknown job kind: " + kind);
        }
    }

    private interface JobBody {
        Object run() throws Exception;
    }

    private Map<String, Object> jobResult(String id, JobBody body) {
        try {
            Object value = body.run();
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("id", id);
            result.put("ok", true);
            result.put("result", Json.write(value));
            return result;
        } catch (Exception e) {
            getLogger().warning("job failed: " + e.getMessage());
            return simpleResult(id, false, e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private Map<String, Object> runConsoleCommand(String id, String command) {
        if (command == null || command.isBlank()) return simpleResult(id, false, "empty command");
        try {
            Boolean ok = Bukkit.getScheduler().callSyncMethod(this, () -> Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command)).get();
            getLogger().info("ran queued command: /" + command);
            return simpleResult(id, ok == null || ok, ok == null || ok ? "executed" : "dispatch returned false");
        } catch (Exception e) {
            return simpleResult(id, false, e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    @SuppressWarnings("deprecation") // Server.Spigot#restart is deprecated but is the only in-process restart
    private Map<String, Object> powerAction(String id, String action, boolean force) {
        // ack first: the JVM is about to go away
        ack(List.of(simpleResult(id, true, action + " requested")));
        Bukkit.getScheduler().runTaskLater(this, () -> {
            if ("stop".equals(action)) {
                Bukkit.shutdown();
            } else {
                // Paper implements Spigot's restart (re-runs server.jar in place)
                Bukkit.getServer().spigot().restart();
            }
        }, 20L);
        return null;
    }

    private Map<String, Object> simpleResult(String id, boolean ok, String message) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("id", id);
        r.put("ok", ok);
        r.put("result", message);
        return r;
    }

    private void ack(List<Map<String, Object>> results) {
        try {
            post("/api/mc/ack", Json.write(Map.of("results", results)));
        } catch (Exception e) {
            getLogger().warning("ack failed: " + e.getMessage());
        }
    }

    // ── commands ─────────────────────────────────────────────────────────────

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sender.sendMessage(ChatColor.AQUA + "TCC" + ChatColor.WHITE + " — اتصال به سایت");
            sender.sendMessage(ChatColor.YELLOW + "/site connect <code>" + ChatColor.GRAY + " — وصل کردن حساب به site.tccmc.ir");
            sender.sendMessage(ChatColor.YELLOW + "/site status" + ChatColor.GRAY + " — وضعیت اتصال این سرور به سایت");
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "connect" -> {
                if (!(sender instanceof Player player)) {
                    sender.sendMessage(ChatColor.RED + "این دستور فقط در بازی قابل استفاده است.");
                    return true;
                }
                if (args.length < 2) {
                    player.sendMessage(ChatColor.RED + "استفاده: /site connect <code>");
                    return true;
                }
                linkAccount(player, args[1]);
                return true;
            }
            case "status" -> {
                String when = lastPushAt == 0 ? "هرگز" : ((System.currentTimeMillis() - lastPushAt) / 1000) + " ثانیه پیش";
                sender.sendMessage(ChatColor.AQUA + "TccBridge" + ChatColor.GRAY + " → " + ChatColor.WHITE + siteUrl);
                sender.sendMessage(ChatColor.GRAY + "توکن: " + (token.isEmpty() ? ChatColor.RED + "تنظیم نشده" : ChatColor.GREEN + "تنظیم شده"));
                sender.sendMessage(ChatColor.GRAY + "آخرین ارسال وضعیت: " + ChatColor.WHITE + when + ChatColor.GRAY + " (" + lastPushResult + ")");
                sender.sendMessage(ChatColor.GRAY + "ریشه فایل‌ها: " + ChatColor.WHITE + fs.root());
                return true;
            }
            case "reload" -> {
                if (!sender.hasPermission("tccbridge.admin")) {
                    sender.sendMessage(ChatColor.RED + "دسترسی ندارید.");
                    return true;
                }
                reloadConfig();
                loadSettings();
                startTasks();
                sender.sendMessage(ChatColor.GREEN + "TccBridge reloaded.");
                return true;
            }
            default -> {
                sender.sendMessage(ChatColor.RED + "دستور ناشناخته. /site");
                return true;
            }
        }
    }

    private void linkAccount(Player player, String code) {
        player.sendMessage(ChatColor.GRAY + "در حال بررسی کد…");
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            String reply;
            try {
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("code", code.trim().toUpperCase());
                body.put("uuid", player.getUniqueId().toString());
                body.put("username", player.getName());
                Response res = post("/api/account/link/confirm", Json.write(body));
                if (res.status == 200) {
                    reply = ChatColor.GREEN + "✔ حساب " + player.getName() + " با موفقیت به سایت وصل شد.";
                    getLogger().info("linked " + player.getName() + " (" + player.getUniqueId() + ")");
                } else if (res.status == 404) {
                    reply = ChatColor.RED + "کد نامعتبر یا منقضی شده است. کد تازه از پنل کاربری بگیر.";
                } else if (res.status == 403) {
                    reply = ChatColor.RED + "توکن پلاگین معتبر نیست (config.yml).";
                } else {
                    reply = ChatColor.RED + "خطا در اتصال (HTTP " + res.status + ").";
                }
            } catch (Exception e) {
                reply = ChatColor.RED + "ارتباط با سایت برقرار نشد: " + e.getClass().getSimpleName();
            }
            String finalReply = reply;
            Bukkit.getScheduler().runTask(this, () -> player.sendMessage(finalReply));
        });
    }

    // ── http helpers ─────────────────────────────────────────────────────────

    private record Response(int status, String body) {}

    private Response post(String path, String jsonBody) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(siteUrl + path))
                .timeout(Duration.ofSeconds(20))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .header("X-TCC-Token", token)
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .build();
        HttpResponse<String> res = http.send(request, HttpResponse.BodyHandlers.ofString());
        return new Response(res.statusCode(), res.body() == null ? "" : res.body());
    }

    private static double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    private static int intOf(Object value, int fallback) {
        return value instanceof Number n ? n.intValue() : fallback;
    }

    private static boolean boolOf(Object value) {
        return value instanceof Boolean b ? b : value != null && Boolean.parseBoolean(String.valueOf(value));
    }

    private static String trim(String s) {
        String t = s.replaceAll("\\s+", " ").trim();
        return t.length() > 160 ? t.substring(0, 160) + "…" : t;
    }
}
