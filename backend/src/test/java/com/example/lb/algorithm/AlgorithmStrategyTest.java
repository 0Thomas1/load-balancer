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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AlgorithmStrategyTest {

    private static final String SOURCE_IP = "10.0.0.1";

    private static Backend backend(String id, int weight) {
        return new Backend(id, "Backend " + id,
                new BackendConfig(1, 0.0, 100, weight));
    }

    private static List<String> idsOf(Iterable<Backend> backends) {
        List<String> ids = new ArrayList<>();
        for (Backend backend : backends) {
            ids.add(backend.id());
        }
        return ids;
    }

    @Nested
    class RoundRobin {

        private final RoundRobinStrategy strategy = new RoundRobinStrategy();

        @Test
        void cyclesThroughCandidatesInOrder() {
            List<Backend> candidates = List.of(backend("b1", 1), backend("b2", 1), backend("b3", 1));

            List<String> picks = new ArrayList<>();
            for (int i = 0; i < 6; i++) {
                picks.add(strategy.select(candidates, SOURCE_IP).id());
            }

            assertThat(picks).containsExactly("b1", "b2", "b3", "b1", "b2", "b3");
        }

        @Test
        void singleCandidateIsAlwaysSelected() {
            Backend only = backend("only", 1);

            assertThat(strategy.select(List.of(only), SOURCE_IP)).isSameAs(only);
            assertThat(strategy.select(List.of(only), SOURCE_IP)).isSameAs(only);
        }

        @Test
        void rejectsEmptyCandidateList() {
            assertThatThrownBy(() -> strategy.select(List.of(), SOURCE_IP))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("no candidates");
        }
    }

    @Nested
    class WeightedRoundRobin {

        private final WeightedRoundRobinStrategy strategy = new WeightedRoundRobinStrategy();

        @Test
        void distributesAccordingToWeights() {
            List<Backend> candidates =
                    List.of(backend("b1", 3), backend("b2", 1), backend("b3", 1));

            Map<String, Integer> counts = new HashMap<>();
            for (int i = 0; i < 50; i++) {
                counts.merge(strategy.select(candidates, SOURCE_IP).id(), 1, Integer::sum);
            }

            assertThat(counts).containsOnlyKeys("b1", "b2", "b3");
            assertThat(counts.get("b1")).isEqualTo(30);
            assertThat(counts.get("b2")).isEqualTo(10);
            assertThat(counts.get("b3")).isEqualTo(10);
        }

        @Test
        void singleCandidateIsAlwaysSelected() {
            Backend only = backend("only", 7);

            assertThat(strategy.select(List.of(only), SOURCE_IP)).isSameAs(only);
        }

        @Test
        void rejectsEmptyCandidateList() {
            assertThatThrownBy(() -> strategy.select(List.of(), SOURCE_IP))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("no candidates");
        }

        @Test
        void fallsBackToEvenRotationWhenAllWeightsAreZero() {
            // BackendConfig rejects weight < 1, so zero weights are only reachable
            // through a stand-in whose reported weight is 0.
            List<Backend> candidates =
                    List.of(zeroWeightBackend("b1"), zeroWeightBackend("b2"), zeroWeightBackend("b3"));

            Map<String, Integer> counts = new HashMap<>();
            for (int i = 0; i < 100; i++) {
                counts.merge(strategy.select(candidates, SOURCE_IP).id(), 1, Integer::sum);
            }

            assertThat(counts).containsOnlyKeys("b1", "b2", "b3");
            assertThat(counts.get("b1")).isBetween(28, 39);
            assertThat(counts.get("b2")).isBetween(28, 39);
            assertThat(counts.get("b3")).isBetween(28, 39);
        }

        private Backend zeroWeightBackend(String id) {
            BackendConfig config = mock(BackendConfig.class);
            when(config.weight()).thenReturn(0);
            Backend backend = mock(Backend.class);
            when(backend.id()).thenReturn(id);
            when(backend.config()).thenReturn(config);
            return backend;
        }
    }

    @Nested
    class Random {

        private static final long SEED = 42L;
        private static final int ITERATIONS = 10_000;
        private static final int MIN_PER_BACKEND = 9_000 / 3;

        @Test
        void picksEveryBackendAtLeastThreeThousandTimes() {
            RandomStrategy strategy = new RandomStrategy(SEED);
            List<Backend> candidates = List.of(backend("b1", 1), backend("b2", 1), backend("b3", 1));

            Map<String, Integer> counts = new HashMap<>();
            for (int i = 0; i < ITERATIONS; i++) {
                counts.merge(strategy.select(candidates, SOURCE_IP).id(), 1, Integer::sum);
            }

            assertThat(counts).containsOnlyKeys("b1", "b2", "b3");
            assertThat(counts.values()).allSatisfy(
                    count -> assertThat(count).isGreaterThanOrEqualTo(MIN_PER_BACKEND));
            assertThat(counts.values().stream().mapToInt(Integer::intValue).sum()).isEqualTo(ITERATIONS);
        }

        @Test
        void sameSeedProducesSameSequence() {
            List<Backend> candidates = List.of(backend("b1", 1), backend("b2", 1), backend("b3", 1));

            assertThat(idsOf(picks(new RandomStrategy(SEED), candidates)))
                    .isEqualTo(idsOf(picks(new RandomStrategy(SEED), candidates)));
        }

        @Test
        void singleCandidateIsAlwaysSelected() {
            Backend only = backend("only", 1);
            RandomStrategy strategy = new RandomStrategy(SEED);

            assertThat(strategy.select(List.of(only), SOURCE_IP)).isSameAs(only);
            assertThat(strategy.select(List.of(only), SOURCE_IP)).isSameAs(only);
        }

        @Test
        void rejectsEmptyCandidateList() {
            assertThatThrownBy(() -> new RandomStrategy(SEED).select(List.of(), SOURCE_IP))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("no candidates");
        }

        private List<Backend> picks(RandomStrategy strategy, List<Backend> candidates) {
            List<Backend> picks = new ArrayList<>();
            for (int i = 0; i < 50; i++) {
                picks.add(strategy.select(candidates, SOURCE_IP));
            }
            return picks;
        }
    }

    @Nested
    class LeastConnections {

        private final LeastConnectionsStrategy strategy = new LeastConnectionsStrategy();

        @Test
        void picksBackendWithFewestActiveConnections() {
            Backend busy = backend("b1", 1);
            Backend busier = backend("b2", 1);
            Backend idle = backend("b3", 1);
            for (int i = 0; i < 2; i++) {
                busy.stats().incrementActive();
            }
            for (int i = 0; i < 5; i++) {
                busier.stats().incrementActive();
            }

            assertThat(strategy.select(List.of(busy, busier, idle), SOURCE_IP)).isSameAs(idle);
        }

        @Test
        void tiesBreakToFirstListedCandidate() {
            Backend first = backend("b1", 1);
            Backend second = backend("b2", 1);

            assertThat(strategy.select(List.of(first, second), SOURCE_IP)).isSameAs(first);
            assertThat(strategy.select(List.of(second, first), SOURCE_IP)).isSameAs(second);
        }

        @Test
        void singleCandidateIsAlwaysSelected() {
            Backend only = backend("only", 1);

            assertThat(strategy.select(List.of(only), SOURCE_IP)).isSameAs(only);
        }

        @Test
        void rejectsEmptyCandidateList() {
            assertThatThrownBy(() -> strategy.select(List.of(), SOURCE_IP))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("no candidates");
        }
    }

    @Nested
    class Names {

        @Test
        void eachStrategyReportsItsContractualName() {
            assertThat(new RoundRobinStrategy().getName()).isEqualTo("round-robin");
            assertThat(new WeightedRoundRobinStrategy().getName()).isEqualTo("weighted-round-robin");
            assertThat(new RandomStrategy().getName()).isEqualTo("random");
            assertThat(new LeastConnectionsStrategy().getName()).isEqualTo("least-connections");
        }
    }
}