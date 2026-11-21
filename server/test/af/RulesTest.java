package af;

import java.time.Duration;
import java.time.Instant;

/** Deterministic rules with no HTTP, clock file or database. */
public final class RulesTest {
    public static void main(String[] args) {
        run("U-01", "allocated points and both completion denominators", () -> {
            double alice = Contribution.allocatedPoints(20, 75);
            equal(15.0, alice);
            equal(60.0, Contribution.percent(60, 100));
            equal(100.0, Contribution.percent(60, 60));
            equal(25.0, Contribution.percent(alice, 60));
        });
        run("U-02", "zero points, zero denominator, historical overage and final rounding", () -> {
            equal(0.0, Contribution.percent(0, 60));
            equal(0.0, Contribution.percent(0, 0));
            equal(0.0, Contribution.percent(0, 100));
            equal(120.0, Contribution.percent(60, 50));
            double sum = Contribution.allocatedPoints(1, 33.33) + Contribution.allocatedPoints(1, 33.33);
            equal(33.3, Contribution.percent(sum, 2));
        });
        run("U-03", "early dissent and missing author confirmation remain pending", () -> {
            equal("pending", Tasks.collectionStatus(0, 2, 1, 1));
            equal("pending", Tasks.collectionStatus(1, 2, 2, 0));
            equal("pending", Tasks.collectionStatus(0, 0, 0, 0));
        });
        run("U-04", "complete collection yields one outcome", () -> {
            equal("rejected", Tasks.collectionStatus(0, 2, 2, 1));
            equal("accepted", Tasks.collectionStatus(0, 2, 2, 0));
        });
        run("U-05", "24 hours from first private reminder, including the exact boundary", () -> {
            Instant first = Instant.parse("2026-11-21T10:07:42.123456789Z");
            Instant deadline = first.plus(Duration.ofHours(24));
            equal(false, Reminders.followUpDue(first, first.plus(Duration.ofHours(23))));
            equal(false, Reminders.followUpDue(first, deadline.minusNanos(1)));
            equal(true, Reminders.followUpDue(first, deadline));
            equal(true, Reminders.followUpDue(first, deadline.plusNanos(1)));
        });
        System.out.println("Unit tests: 5 cases passed");
    }

    private static void run(String id, String name, Runnable test) {
        test.run();
        System.out.println("PASS " + id + " " + name);
    }

    private static void equal(Object expected, Object actual) {
        if (!expected.equals(actual)) throw new AssertionError("Expected " + expected + ", got " + actual);
    }
}
