package org.amit.finwise.marketdata.ops;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.amit.finwise.cfo.service.notification.EmailNotificationService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Postgres ops baseline (DF-5): nightly {@code pg_dump} to a configured (ideally
 * off-box) directory, with retention pruning and an email alert on failure.
 *
 * The datasource URL is parsed for host/port/db; the password is passed via the
 * {@code PGPASSWORD} environment variable so it never lands in the process
 * argument list. Disabled by default — set {@code marketdata.backup.enabled=true}
 * and a writable {@code marketdata.backup.dir} to turn it on.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PgBackupService {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");
    private static final Pattern JDBC_PG = Pattern.compile(
            "jdbc:postgresql://([^:/]+)(?::(\\d+))?/([^?;]+)");

    private final EmailNotificationService emailService;

    @Value("${spring.datasource.url}")
    private String datasourceUrl;

    @Value("${spring.datasource.username:postgres}")
    private String dbUser;

    @Value("${spring.datasource.password:}")
    private String dbPassword;

    @Value("${marketdata.backup.enabled:false}")
    private boolean enabled;

    @Value("${marketdata.backup.dir:./backups}")
    private String backupDir;

    @Value("${marketdata.backup.pg-dump-path:pg_dump}")
    private String pgDumpPath;

    @Value("${marketdata.backup.retain-days:7}")
    private int retainDays;

    @Value("${marketdata.backup.timeout-minutes:30}")
    private int timeoutMinutes;

    /** Run a dump now; returns a status map (also used by the admin endpoint). */
    public Map<String, Object> dumpNow() {
        Map<String, Object> result = new LinkedHashMap<>();
        if (!enabled) {
            result.put("status", "DISABLED");
            result.put("hint", "set marketdata.backup.enabled=true and marketdata.backup.dir");
            return result;
        }

        Matcher m = JDBC_PG.matcher(datasourceUrl);
        if (!m.find()) {
            result.put("status", "FAILED");
            result.put("error", "datasource URL is not jdbc:postgresql://host[:port]/db");
            return result;
        }
        String host = m.group(1);
        String port = m.group(2) != null ? m.group(2) : "5432";
        String db = m.group(3);

        try {
            Path dir = Path.of(backupDir);
            Files.createDirectories(dir);
            Path outFile = dir.resolve(db + "_" + LocalDateTime.now(IST).format(STAMP) + ".dump");

            ProcessBuilder pb = new ProcessBuilder(
                    pgDumpPath, "-h", host, "-p", port, "-U", dbUser,
                    "-F", "c", "-f", outFile.toString(), db);
            pb.environment().put("PGPASSWORD", dbPassword);
            pb.redirectErrorStream(true);

            Process proc = pb.start();
            String output = new String(proc.getInputStream().readAllBytes());
            boolean finished = proc.waitFor(Duration.ofMinutes(timeoutMinutes));
            if (!finished) {
                proc.destroyForcibly();
                throw new IOException("pg_dump timed out after " + timeoutMinutes + " min");
            }
            int exit = proc.exitValue();
            if (exit != 0) {
                throw new IOException("pg_dump exit " + exit + ": " + truncate(output));
            }

            long bytes = Files.size(outFile);
            int pruned = pruneOldDumps(dir, db);
            result.put("status", "SUCCESS");
            result.put("file", outFile.toString());
            result.put("bytes", bytes);
            result.put("prunedOldDumps", pruned);
            log.info("[PgBackup] dump OK: {} ({} bytes), pruned {}", outFile, bytes, pruned);
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            log.error("[PgBackup] dump failed: {}", e.getMessage());
            result.put("status", "FAILED");
            result.put("error", e.getMessage());
            emailService.sendPortfolioAlert("Postgres backup failed",
                    "The nightly pg_dump failed:\n\n" + e.getMessage());
        }
        return result;
    }

    /** Delete dumps for this db older than the retention window. */
    private int pruneOldDumps(Path dir, String db) {
        long cutoff = System.currentTimeMillis() - retainDays * 24L * 3600_000L;
        int pruned = 0;
        try (Stream<Path> files = Files.list(dir)) {
            for (Path p : files.filter(p -> p.getFileName().toString().startsWith(db + "_")
                    && p.getFileName().toString().endsWith(".dump")).toList()) {
                if (Files.getLastModifiedTime(p).toMillis() < cutoff && Files.deleteIfExists(p)) {
                    pruned++;
                }
            }
        } catch (IOException e) {
            log.warn("[PgBackup] prune failed: {}", e.getMessage());
        }
        return pruned;
    }

    private static String truncate(String s) {
        if (s == null) return null;
        return s.length() <= 400 ? s : s.substring(0, 400);
    }
}
