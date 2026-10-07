package com.example.lb.algorithm;

import com.example.lb.backend.Backend;
import com.example.lb.backend.BackendConfig;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HashStrategyTest {

    private static final int IP_COUNT = 100;
    private static final int TOLERANCE = 20;
    private static final int MIN_STABLE_SHARE = 80;

    /**
     * Fixed IP set so distribution and stability assertions never flake.
     *
     * <p>Removing one of four backends only preserves the keys that were not pointing at
     * it, so the expected stability is 75%. The pinned 80% floor is above that mean and
     * therefore only reachable when the removed backend holds at most 20 keys, which this
     * fixture satisfies (13 keys). {@code #removingABackendMovesOnlyItsOwnKeys} pins the
     * underlying invariant, which holds regardless of the fixture.
     */
    private static List<String> fixedIps() {
        List<String> ips = new ArrayList<>(IP_COUNT);
        for (int i = 0; i < IP_COUNT; i++) {
            ips.add("192.168.1." + i);
        }
        return ips;
    }

    private static Backend backend(String id) {
        return new Backend(id, "Backend " + id, new BackendConfig(1, 0.0, 100, 1));
    }

    private static List<Backend> backends(String... ids) {
        List<Backend> backends = new ArrayList<>(ids.length);
        for (String id : ids) {
            backends.add(backend(id));
        }
        return backends;
    }

    private static Map<String, String> pickPerIp(AlgorithmStrategy strategy, List<Backend> candidates,
            List<String> ips) {
        Map<String, String> picks = new HashMap<>();
        for (String ip : ips) {
            picks.put(ip, strategy.select(candidates, ip).id());
        }
        return picks;
    }

    @Nested
    class IpHash {

        private final IPHashStrategy strategy = new IPHashStrategy();

        @Test
        void sameIpAlwaysMapsToSameBackend() {
            List<Backend> candidates = backends("b1", "b2", "b3");

            String first = strategy.select(candidates, "192.168.1.42").id();
            for (int i = 0; i < 50; i++) {
                assertThat(strategy.select(candidates, "192.168.1.42").id()).isEqualTo(first);
            }
        }

        @Test
        void differentIpsSpreadAcrossCandidates() {
            List<Backend> candidates = backends("b1", "b2", "b3");

            Map<String, String> picks = pickPerIp(strategy, candidates, fixedIps());

            assertThat(picks.values()).containsAll(candidates.stream().map(Backend::id).toList());
        }

        @Test
        void blankIpIsDeterministicAndDoesNotThrow() {
            List<Backend> candidates = backends("b1", "b2", "b3");

            String blank = strategy.select(candidates, "").id();
            String nulled = strategy.select(candidates, null).id();
            String whitespace = strategy.select(candidates, "   ").id();

            assertThat(blank).isEqualTo(strategy.select(candidates, "").id());
            assertThat(nulled).isEqualTo(blank);
            assertThat(whitespace).isEqualTo(blank);
        }

        @Test
        void singleCandidateIsAlwaysSelected() {
            Backend only = backend("only");

            assertThat(strategy.select(List.of(only), "10.0.0.1")).isSameAs(only);
        }

        @Test
        void rejectsEmptyCandidateList() {
            assertThatThrownBy(() -> strategy.select(List.of(), "10.0.0.1"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("no candidates");
        }
    }

    @Nested
    class ConsistentHash {

        private final ConsistentHashStrategy strategy = new ConsistentHashStrategy();

        @Test
        void reportsItsContractualName() {
            assertThat(strategy.getName()).isEqualTo("consistent-hash");
        }

        @Test
        void sameIpAlwaysMapsToSameBackend() {
            List<Backend> candidates = backends("b1", "b2", "b3", "b4");

            String first = strategy.select(candidates, "192.168.1.42").id();
            for (int i = 0; i < 50; i++) {
                assertThat(strategy.select(candidates, "192.168.1.42").id()).isEqualTo(first);
            }
        }

        @Test
        void distributesAcrossEveryBackendRoughlyEvenly() {
            List<Backend> candidates = backends("b1", "b2", "b3", "b4");

            Map<String, Integer> counts = new HashMap<>();
            for (String id : pickPerIp(strategy, candidates, fixedIps()).values()) {
                counts.merge(id, 1, Integer::sum);
            }

            int expected = IP_COUNT / candidates.size();
            assertThat(counts).containsOnlyKeys("b1", "b2", "b3", "b4");
            assertThat(counts.values()).allSatisfy(
                    count -> assertThat(count).isBetween(expected - TOLERANCE, expected + TOLERANCE));
            assertThat(counts.values().stream().mapToInt(Integer::intValue).sum()).isEqualTo(IP_COUNT);
        }

        @Test
        void removingOneBackendKeepsMostIpsOnTheSameBackend() {
            List<Backend> before = backends("b1", "b2", "b3", "b4");
            List<Backend> after = backends("b1", "b2", "b4");
            List<String> ips = fixedIps();

            Map<String, String> beforePicks = pickPerIp(strategy, before, ips);
            Map<String, String> afterPicks = pickPerIp(strategy, after, ips);

            long stable = ips.stream()
                    .filter(ip -> beforePicks.get(ip).equals(afterPicks.get(ip)))
                    .count();
            assertThat(stable).isGreaterThanOrEqualTo(MIN_STABLE_SHARE);
        }

        /**
         * The defining property of consistent hashing, and independent of how evenly the
         * fixture happens to spread: only the keys that were pointing at the removed
         * backend move, every other key keeps its backend.
         */
        @Test
        void removingABackendMovesOnlyItsOwnKeys() {
            String removed = "b3";
            List<Backend> before = backends("b1", "b2", "b3", "b4");
            List<Backend> after = backends("b1", "b2", "b4");
            List<String> ips = fixedIps();

            Map<String, String> beforePicks = pickPerIp(strategy, before, ips);
            Map<String, String> afterPicks = pickPerIp(strategy, after, ips);

            assertThat(ips).allSatisfy(ip -> {
                if (removed.equals(beforePicks.get(ip))) {
                    assertThat(afterPicks.get(ip)).isNotEqualTo(removed);
                } else {
                    assertThat(afterPicks.get(ip)).isEqualTo(beforePicks.get(ip));
                }
            });
        }

        @Test
        void blankIpIsDeterministicAndDoesNotThrow() {
            List<Backend> candidates = backends("b1", "b2", "b3", "b4");

            String blank = strategy.select(candidates, "").id();
            String nulled = strategy.select(candidates, null).id();

            assertThat(blank).isEqualTo(strategy.select(candidates, "").id());
            assertThat(nulled).isEqualTo(blank);
        }

        @Test
        void singleCandidateIsAlwaysSelected() {
            Backend only = backend("only");

            assertThat(strategy.select(List.of(only), "10.0.0.1")).isSameAs(only);
        }

        @Test
        void rejectsEmptyCandidateList() {
            assertThatThrownBy(() -> strategy.select(List.of(), "10.0.0.1"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("no candidates");
        }
    }

    @Nested
    class Names {

        @Test
        void hashStrategiesReportTheirContractualNames() {
            assertThat(new IPHashStrategy().getName()).isEqualTo("ip-hash");
            assertThat(new ConsistentHashStrategy().getName()).isEqualTo("consistent-hash");
        }
    }
}