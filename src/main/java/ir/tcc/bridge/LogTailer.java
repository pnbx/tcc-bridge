package ir.tcc.bridge;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Tails logs/latest.log and hands out only the lines written since the last
 * call, so the website's console page shows live output without the panel.
 */
final class LogTailer {

    private static final Pattern LINE = Pattern.compile("^\\[(\\d{2}:\\d{2}:\\d{2})\\](?:\\s*\\[([^/\\]]+)/([A-Z]+)\\])?:?\\s*(.*)$");

    private final Path logFile;
    private long offset = 0;

    LogTailer(File serverRoot) {
        this.logFile = serverRoot.toPath().resolve("logs").resolve("latest.log");
    }

    List<Map<String, Object>> poll(int maxLines) {
        if (!Files.isRegularFile(logFile)) return List.of();

        try (RandomAccessFile raf = new RandomAccessFile(logFile.toFile(), "r")) {
            long length = raf.length();
            if (length < offset) offset = 0; // rotated or truncated
            if (length == offset) return List.of();
            // never walk more than ~512KB in one go (large bursts are fine to skip)
            long start = Math.max(offset, length - 512 * 1024);
            raf.seek(start);
            byte[] buf = new byte[(int) (length - start)];
            raf.readFully(buf);
            offset = length;

            String text = new String(buf, StandardCharsets.UTF_8);
            String[] rawLines = text.split("\\r?\\n");
            List<Map<String, Object>> out = new ArrayList<>();
            for (String raw : rawLines) {
                if (raw.isBlank()) continue;
                out.add(parse(raw));
            }
            if (out.size() > maxLines) out = out.subList(out.size() - maxLines, out.size());
            return out;
        } catch (IOException e) {
            return List.of();
        }
    }

    private Map<String, Object> parse(String raw) {
        Map<String, Object> line = new LinkedHashMap<>();
        Matcher m = LINE.matcher(raw);
        if (m.matches()) {
            line.put("ts", timeToEpoch(m.group(1)));
            line.put("thread", m.group(2) == null ? "Server thread" : m.group(2));
            line.put("level", m.group(3) == null ? "INFO" : m.group(3));
            line.put("message", m.group(4));
        } else {
            line.put("ts", System.currentTimeMillis());
            line.put("level", raw.contains("WARN") ? "WARN" : raw.contains("ERROR") ? "ERROR" : "OTHER");
            line.put("thread", "-");
            line.put("message", raw);
        }
        return line;
    }

    private long timeToEpoch(String hhmmss) {
        try {
            String[] parts = hhmmss.split(":");
            LocalTime time = LocalTime.of(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]), Integer.parseInt(parts[2]));
            // today's date in the server's zone; when the log crossed midnight the
            // ordering stays monotonic because we only ever append
            return java.time.LocalDate.now(ZoneId.systemDefault()).atTime(time).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        } catch (Exception e) {
            return System.currentTimeMillis();
        }
    }
}
