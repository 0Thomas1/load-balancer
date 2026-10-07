package com.example.lb.algorithm;

import com.example.lb.backend.Backend;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Consistent hashing: each backend contributes {@value #VIRTUAL_NODES} points to a ring
 * spread over the full 31-bit hash range, and a source IP is served by the first ring
 * point at or after its own hash. Dropping a backend therefore only moves the keys that
 * were pointing at it, instead of reshuffling every key.
 *
 * <p>The ring is rebuilt from {@code candidates} on each call, so it always reflects the
 * current healthy set.
 */
@Component
public class ConsistentHashStrategy implements AlgorithmStrategy {

    public static final String NAME = "consistent-hash";
    static final int VIRTUAL_NODES = 100;

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public Backend select(List<Backend> candidates, String sourceIp) {
        AlgorithmStrategy.requireNonEmpty(candidates);

        MessageDigest digest = md5();
        List<Point> ring = ring(candidates, digest);
        int hash = hash(digest, normalize(sourceIp));

        // Walk clockwise to the first point at or after the hash, wrapping around.
        for (Point point : ring) {
            if (point.hash() >= hash) {
                return point.backend();
            }
        }
        return ring.get(0).backend();
    }

    /** Builds the ring, ordered clockwise by hash point. */
    private static List<Point> ring(List<Backend> candidates, MessageDigest digest) {
        List<Point> ring = new ArrayList<>(candidates.size() * VIRTUAL_NODES);
        for (Backend candidate : candidates) {
            for (int i = 0; i < VIRTUAL_NODES; i++) {
                ring.add(new Point(hash(digest, candidate.id() + "-" + i), candidate));
            }
        }
        // Stable sort: ties between colliding points resolve in candidate order.
        ring.sort(Comparator.comparingInt(Point::hash));
        return ring;
    }

    /** Reduces a possibly missing or padded IP to a stable hash input. */
    static String normalize(String sourceIp) {
        return sourceIp == null ? "" : sourceIp.trim();
    }

    static MessageDigest md5() {
        try {
            return MessageDigest.getInstance("MD5");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("MD5 digest unavailable", e);
        }
    }

    /**
     * Folds an MD5 digest into a non-negative int covering the full 31-bit range.
     * Masks instead of using {@link Math#abs}, which is still negative for
     * {@link Integer#MIN_VALUE}.
     */
    static int hash(MessageDigest digest, String key) {
        digest.reset();
        byte[] bytes = digest.digest(key.getBytes(StandardCharsets.UTF_8));
        int value = ((bytes[0] & 0xff) << 24)
                | ((bytes[1] & 0xff) << 16)
                | ((bytes[2] & 0xff) << 8)
                | (bytes[3] & 0xff);
        return value & 0x7fffffff;
    }

    private record Point(int hash, Backend backend) {
    }
}