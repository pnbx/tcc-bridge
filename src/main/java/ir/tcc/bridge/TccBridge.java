package ir.tcc.bridge;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

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
 * host drops foreign traffic), so this plugin drives the connection outward:
 *
 *   push  : real status (players, MOTD, version, TPS, MSPT) every N seconds
 *   poll  : collect queued console commands (store rank grants) and run them
 *   link  : /site connect &lt;code&gt; proves account ownership in game
 *
 * Endpoints used: POST /api/mc/ingest, /api/mc/poll, /api/mc/ack and
 * /api/account/link/confirm — all authenticated with the shared token header.
 */
public final class TccBridge extends JavaPlugin {

    private HttpClient http;
    private String siteUrl;
    private String token;
    private int pushSeconds;
    private int pollSeconds;
    private BukkitTask pushTask;
    private BukkitTask pollTask;

    private final AtomicBoolean pushInFlight = new AtomicBoolean(false);
    private final AtomicBoolean pollInFlight = new AtomicBoolean(false);

    /** Last successful push, surfaced through /site status. */
    private volatile long lastPushAt = 0L;
    private volatile String lastPushResult = "هرگز";

    @Override
    public void onEnable() {
        saveDefaultConfig();
        siteUrl = getConfig().getString("site-url", "https://site.tccmc.ir").replaceAll("/+$", "");
        token = getConfig().getString("token", "").trim();
        pushSeconds = Math.max(5, getConfig().getInt("push-interval-seconds", 20));
        pollSeconds = Math.max(5, getConfig().getInt("poll-interval-seconds", 10));

        http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .version(HttpClient.Version.HTTP_1_1)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();

        if (token.isEmpty()) {
            getLogger().warning("token is empty — put the website's MC_INGEST_TOKEN in plugins/TccBridge/config.yml, then /site reload");
        }

        startTasks();
        getLogger().info("TccBridge enabled → " + siteUrl + " (push " + pushSeconds + "s, poll " + pollSeconds + "s)");
    }

    @Override
    public void onDisable() {
        stopTasks();
        getLogger().info("TccBridge disabled.");
    }

    private void startTasks() {
        stopTasks();
        // small initial delay so a restart does not race the server startup
        pushTask = Bukkit.getScheduler().runTaskTimerAsynchronously(this, this::pushStatus, 60L, pushSeconds * 20L);
        pollTask = Bukkit.getScheduler().runTaskTimerAsynchronously(this, this::pollCommands, 120L, pollSeconds * 20L);
    }

    private void stopTasks() {
        if (pushTask != null) pushTask.cancel();
        if (pollTask != null) pollTask.cancel();
    }

    // ── status push ──────────────────────────────────────────────────────────

    private void pushStatus() {
        if (token.isEmpty() || !pushInFlight.compareAndSet(false, true)) return;
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("playersOnline", Bukkit.getOnlinePlayers().size());
            body.put("playersMax", Bukkit.getMaxPlayers());
            body.put("version", Bukkit.getMinecraftVersion());
            body.put("motd", List.of(ChatColor.stripColor(Bukkit.getMotd() == null ? "TCC Network" : Bukkit.getMotd())));
            double[] tps = Bukkit.getServer().getTPS();
            if (tps.length > 0) body.put("tps", round2(tps[0]));
            body.put("mspt", round2(Bukkit.getServer().getAverageTickTime()));
            body.put("source", "plugin");

            Response res = post("/api/mc/ingest", Json.write(body));
            if (res.status == 200) {
                lastPushAt = System.currentTimeMillis();
                lastPushResult = "موفق";
            } else {
                lastPushResult = "خطای " + res.status + (res.body.isEmpty() ? "" : " — " + trim(res.body));
                getLogger().warning("status push failed: HTTP " + res.status + " " + trim(res.body));
            }
        } catch (Exception e) {
            lastPushResult = "خطا — " + e.getClass().getSimpleName();
            getLogger().warning("status push error: " + e.getMessage());
        } finally {
            pushInFlight.set(false);
        }
    }

    // ── command queue pull ───────────────────────────────────────────────────

    private void pollCommands() {
        if (token.isEmpty() || !pollInFlight.compareAndSet(false, true)) return;
        try {
            Response res = post("/api/mc/poll", "{}");
            if (res.status != 200) {
                if (res.status != 401) getLogger().warning("poll failed: HTTP " + res.status);
                return;
            }
            Map<String, Object> root = Json.object(Json.read(res.body));
            List<Object> commands = Json.array(Json.object(root.get("data")).get("commands"));
            if (commands.isEmpty()) return;

            List<Map<String, Object>> results = new ArrayList<>();
            for (Object raw : commands) {
                Map<String, Object> entry = Json.object(raw);
                String id = Json.string(entry.get("id"));
                String command = Json.string(entry.get("command"));
                if (id == null || command == null) continue;
                if (Bukkit.isPrimaryThread()) {
                    results.add(runCommand(id, command));
                } else {
                    // The Bukkit API must be touched from the main thread.
                    Bukkit.getScheduler().runTask(this, () -> {
                        Map<String, Object> r = runCommand(id, command);
                        Bukkit.getScheduler().runTaskAsynchronously(this, () -> ack(List.of(r)));
                    });
                }
            }
            if (!results.isEmpty()) ack(results);
        } catch (Exception e) {
            getLogger().warning("poll error: " + e.getMessage());
        } finally {
            pollInFlight.set(false);
        }
    }

    private Map<String, Object> runCommand(String id, String command) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", id);
        try {
            boolean ok = Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command);
            result.put("ok", ok);
            result.put("result", ok ? "executed" : "dispatch returned false");
            getLogger().info("ran queued command: /" + command + (ok ? "" : " (returned false)"));
        } catch (Exception e) {
            result.put("ok", false);
            result.put("result", e.getClass().getSimpleName() + ": " + e.getMessage());
            getLogger().warning("queued command failed: /" + command + " → " + e.getMessage());
        }
        return result;
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
                return true;
            }
            case "reload" -> {
                if (!sender.hasPermission("tccbridge.admin")) {
                    sender.sendMessage(ChatColor.RED + "دسترسی ندارید.");
                    return true;
                }
                reloadConfig();
                siteUrl = getConfig().getString("site-url", "https://site.tccmc.ir").replaceAll("/+$", "");
                token = getConfig().getString("token", "").trim();
                pushSeconds = Math.max(5, getConfig().getInt("push-interval-seconds", 20));
                pollSeconds = Math.max(5, getConfig().getInt("poll-interval-seconds", 10));
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

    // ── http ─────────────────────────────────────────────────────────────────

    private record Response(int status, String body) {}

    private Response post(String path, String jsonBody) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(siteUrl + path))
                .timeout(Duration.ofSeconds(15))
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

    private static String trim(String s) {
        String t = s.replaceAll("\\s+", " ").trim();
        return t.length() > 160 ? t.substring(0, 160) + "…" : t;
    }
}
