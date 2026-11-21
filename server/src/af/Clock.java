package af;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * Wall clock used by the application. Tests can point AF_CLOCK_FILE at a file
 * containing an ISO-8601 instant; the file is read for every request so a test
 * can advance time without restarting the server.
 */
public final class Clock {
    private Clock() {}

    public static Instant now() {
        String file = System.getenv("AF_CLOCK_FILE");
        if (file != null && !file.isBlank()) {
            Path path = Path.of(file);
            if (Files.isRegularFile(path)) {
                try {
                    String raw = Files.readString(path).trim();
                    if (!raw.isEmpty()) {
                        return Instant.parse(raw).truncatedTo(ChronoUnit.SECONDS);
                    }
                } catch (Exception ex) {
                    System.err.println("[clock] ignoring invalid AF_CLOCK_FILE: " + ex.getMessage());
                }
            }
        }
        String fixed = System.getenv("AF_FIXED_CLOCK");
        if (fixed != null && !fixed.isBlank()) {
            try {
                return Instant.parse(fixed.trim()).truncatedTo(ChronoUnit.SECONDS);
            } catch (Exception ignored) {
                // fall through to the real clock
            }
        }
        return Instant.now().truncatedTo(ChronoUnit.SECONDS);
    }

    public static String iso(Instant instant) {
        return instant.toString();
    }

    public static String nowIso() {
        return iso(now());
    }
}
