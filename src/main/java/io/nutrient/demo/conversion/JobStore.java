package io.nutrient.demo.conversion;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** One temporary folder per request. Folders are deleted after the retention time. */
@Component
public class JobStore {

    private static final Logger log = LoggerFactory.getLogger(JobStore.class);
    private static final Pattern JOB_ID = Pattern.compile("[0-9a-f]{32}");
    private static final Pattern SAFE_NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._ -]{0,150}");

    private final Path root;
    private final Duration retention;

    public JobStore(@Value("${jobs.dir:}") String dir,
                    @Value("${jobs.retention-minutes:60}") long retentionMinutes) throws IOException {
        this.root = (dir == null || dir.isBlank())
                ? Path.of(System.getProperty("java.io.tmpdir"), "nutrient-conversion-jobs")
                : Path.of(dir);
        this.retention = Duration.ofMinutes(retentionMinutes);
        Files.createDirectories(root);
    }

    public record Job(String id, Path inputs, Path outputs) {}

    public Job create() {
        String id = UUID.randomUUID().toString().replace("-", "");
        Path folder = root.resolve(id);
        try {
            Files.createDirectories(folder.resolve("in"));
            Files.createDirectories(folder.resolve("out"));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return new Job(id, folder.resolve("in"), folder.resolve("out"));
    }

    /** An output file of a job, or empty when the id or the name is not valid. */
    public Optional<Path> output(String jobId, String fileName) {
        if (!JOB_ID.matcher(jobId).matches() || !SAFE_NAME.matcher(fileName).matches()) {
            return Optional.empty();
        }
        Path file = root.resolve(jobId).resolve("out").resolve(fileName).normalize();
        return file.startsWith(root) && Files.isRegularFile(file) ? Optional.of(file) : Optional.empty();
    }

    public Path thumbnailFor(Path output) {
        return output.resolveSibling("." + output.getFileName() + ".png");
    }

    public void delete(Job job) {
        deleteQuietly(job.inputs().getParent());
    }

    /** Turns a client file name into a safe local name, keeping the extension. */
    public static String safeName(String original, int index) {
        String base = original == null ? "" : original.replace('\\', '/');
        base = base.substring(base.lastIndexOf('/') + 1).replaceAll("[^A-Za-z0-9._ -]", "_").trim();
        if (base.isEmpty() || base.startsWith(".")) {
            base = "file" + base;
        }
        return String.format("%02d-%s", index + 1, base.length() > 120 ? base.substring(base.length() - 120) : base);
    }

    @Scheduled(fixedDelayString = "PT10M", initialDelayString = "PT1M")
    public void deleteExpiredJobs() {
        Instant limit = Instant.now().minus(retention);
        try (Stream<Path> jobs = Files.list(root)) {
            jobs.filter(p -> lastModified(p).toInstant().isBefore(limit)).forEach(p -> {
                deleteQuietly(p);
                log.debug("Deleted expired job {}", p.getFileName());
            });
        } catch (IOException e) {
            log.warn("Could not clean the job folder: {}", e.getMessage());
        }
    }

    private static FileTime lastModified(Path path) {
        try {
            return Files.getLastModifiedTime(path);
        } catch (IOException e) {
            return FileTime.from(Instant.now());
        }
    }

    static void deleteQuietly(Path path) {
        if (path == null || !Files.exists(path)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(path)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // Best effort: the scheduled clean-up tries again later.
                }
            });
        } catch (IOException ignored) {
            // Best effort.
        }
    }
}
