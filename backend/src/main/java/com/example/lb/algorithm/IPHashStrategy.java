package com.example.lb.algorithm;

import com.example.lb.backend.Backend;
import org.springframework.stereotype.Component;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;

/**
 * Deterministic IP hashing: the source IP is hashed with MD5 and reduced modulo the
 * candidate count, so the same client always lands on the same backend for as long as
 * the candidate list is unchanged.
 */
@Component
public class IPHashStrategy implements AlgorithmStrategy {

    public static final String NAME = "ip-hash";

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public Backend select(List<Backend> candidates, String sourceIp) {
        AlgorithmStrategy.requireNonEmpty(candidates);

        MessageDigest digest = ConsistentHashStrategy.md5();
        int hash = ConsistentHashStrategy.hash(digest, ConsistentHashStrategy.normalize(sourceIp));
        return candidates.get(hash % candidates.size());
    }
}