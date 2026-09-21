package ir.tcc.bridge;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Sandboxed filesystem access for the website's file manager.
 *
 * Every path is resolved inside the server root and anything that tries to
 * escape it (.., absolute paths, symlinks leaving the root) is rejected. The
 * website applies its own sensitive-file policy on top of this.
 */
final class Fs {

    private final Path root;

    Fs(File serverRoot) {
        this.root = serverRoot.toPath().toAbsolutePath().normalize();
    }

    Path root() {
        return root;
    }

    private Path resolve(String relPath) throws IOException {
        String cleaned = (relPath == null ? "" : relPath).replace('\\', '/').trim();
        while (cleaned.startsWith("/")) cleaned = cleaned.substring(1);
        Path resolved = root.resolve(cleaned).normalize();
        if (!resolved.equals(root) && !resolved.startsWith(root)) {
            throw new IOException("path escapes the server sandbox: " + relPath);
        }
        return resolved;
    }

    private String rel(Path path) {
        String s = root.relativize(path).toString().replace('\\', '/');
        return s;
    }

    List<Map<String, Object>> list(String relDir) throws IOException {
        Path dir = resolve(relDir);
        if (!Files.isDirectory(dir)) throw new IOException("not a directory: " + relDir);
        List<Map<String, Object>> out = new ArrayList<>();
        try (var stream = Files.list(dir)) {
            for (Path p : stream.limit(3000).toList()) {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("name", p.getFileName().toString());
                entry.put("path", rel(p));
                entry.put("type", Files.isDirectory(p) ? "dir" : "file");
                entry.put("size", Files.isRegularFile(p) ? Files.size(p) : null);
                entry.put("modifiedAt", Files.getLastModifiedTime(p).toInstant().toString());
                out.add(entry);
            }
        }
        out.sort(Comparator
                .comparing((Map<String, Object> e) -> !"dir".equals(e.get("type")))
                .thenComparing(e -> String.valueOf(e.get("name")).toLowerCase()));
        return out;
    }

    Map<String, Object> read(String relPath, int limitBytes) throws IOException {
        Path file = resolve(relPath);
        if (!Files.isRegularFile(file)) throw new IOException("not a file: " + relPath);
        long size = Files.size(file);
        byte[] all = Files.readAllBytes(file);
        int limit = limitBytes <= 0 ? 512 * 1024 : Math.min(limitBytes, 4 * 1024 * 1024);
        boolean truncated = all.length > limit;
        byte[] slice = truncated ? java.util.Arrays.copyOf(all, limit) : all;
        boolean binary = false;
        for (int i = 0; i < Math.min(slice.length, 8192); i++) {
            if (slice[i] == 0) { binary = true; break; }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("path", relPath);
        out.put("size", size);
        out.put("truncated", truncated);
        out.put("encoding", binary ? "binary" : "utf8");
        out.put("content", binary
                ? java.util.Base64.getEncoder().encodeToString(slice)
                : new String(slice, StandardCharsets.UTF_8));
        return out;
    }

    void write(String relPath, String content, boolean exclusive) throws IOException {
        Path file = resolve(relPath);
        if (Files.isDirectory(file)) throw new IOException("path is a directory: " + relPath);
        Files.createDirectories(file.getParent());
        if (exclusive && Files.exists(file)) throw new IOException("file already exists: " + relPath);
        Files.writeString(file, content == null ? "" : content, StandardCharsets.UTF_8);
    }

    void delete(String relPath) throws IOException {
        Path target = resolve(relPath);
        if (target.equals(root)) throw new IOException("refusing to delete the server root");
        if (!Files.exists(target)) throw new IOException("not found: " + relPath);
        try (var walk = Files.walk(target)) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) Files.delete(p);
        }
    }

    void mkdir(String relPath) throws IOException {
        Files.createDirectories(resolve(relPath));
    }

    void rename(String from, String to) throws IOException {
        Path src = resolve(from);
        Path dst = resolve(to);
        if (!Files.exists(src)) throw new IOException("not found: " + from);
        Files.createDirectories(dst.getParent());
        Files.move(src, dst, StandardCopyOption.REPLACE_EXISTING);
    }

    List<Map<String, Object>> search(String relDir, String pattern) throws IOException {
        Path dir = resolve(relDir);
        String needle = pattern == null ? "" : pattern.toLowerCase();
        List<Map<String, Object>> out = new ArrayList<>();
        if (needle.isEmpty()) return out;
        try (var walk = Files.walk(dir, 8)) {
            for (Path p : walk.toList()) {
                if (out.size() >= 200) break;
                if (!Files.isRegularFile(p)) continue;
                String name = p.getFileName().toString().toLowerCase();
                if (!(name.endsWith(".log") || name.endsWith(".txt") || name.endsWith(".yml") || name.endsWith(".yaml")
                        || name.endsWith(".json") || name.endsWith(".properties") || name.endsWith(".conf")
                        || name.endsWith(".sk") || name.endsWith(".sh"))) continue;
                if (Files.size(p) > 20L * 1024 * 1024) continue;
                List<String> lines = Files.readAllLines(p, StandardCharsets.UTF_8);
                for (int i = 0; i < lines.size() && out.size() < 200; i++) {
                    if (lines.get(i).toLowerCase().contains(needle)) {
                        Map<String, Object> hit = new LinkedHashMap<>();
                        hit.put("path", rel(p));
                        hit.put("line", i + 1);
                        hit.put("text", lines.get(i).length() > 400 ? lines.get(i).substring(0, 400) : lines.get(i));
                        out.add(hit);
                    }
                }
            }
        }
        return out;
    }
}
