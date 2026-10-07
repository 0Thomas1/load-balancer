package com.example.lb.backend;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BackendConfigTest {

    @Test
    void rejectsNegativeLatency() {
        assertThatThrownBy(() -> new BackendConfig(-1, 0.0, 100, 1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsFailureRateAboveOne() {
        assertThatThrownBy(() -> new BackendConfig(5, 1.5, 100, 1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsNegativeFailureRate() {
        assertThatThrownBy(() -> new BackendConfig(5, -0.1, 100, 1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsCapacityBelowOne() {
        assertThatThrownBy(() -> new BackendConfig(5, 0.0, 0, 1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsWeightBelowOne() {
        assertThatThrownBy(() -> new BackendConfig(5, 0.0, 100, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void defaultsAreLatencyFiveAndCapacityHundred() {
        BackendConfig config = BackendConfig.defaults();

        assertThat(config.latencyMs()).isEqualTo(5);
        assertThat(config.failureRate()).isEqualTo(0.0);
        assertThat(config.capacity()).isEqualTo(100);
        assertThat(config.weight()).isEqualTo(1);
    }

    @Test
    void copyWithUpdatesOnlyNonNullFields() {
        BackendConfig config = new BackendConfig(5, 0.2, 100, 1);

        BackendConfig updated = config.copyWith(25L, null, null, 4);

        assertThat(updated.latencyMs()).isEqualTo(25);
        assertThat(updated.failureRate()).isEqualTo(0.2);
        assertThat(updated.capacity()).isEqualTo(100);
        assertThat(updated.weight()).isEqualTo(4);
    }

    @Test
    void copyWithAllNullKeepsCurrentValues() {
        BackendConfig config = new BackendConfig(7, 0.3, 42, 3);

        BackendConfig copy = config.copyWith(null, null, null, null);

        assertThat(copy.latencyMs()).isEqualTo(7);
        assertThat(copy.failureRate()).isEqualTo(0.3);
        assertThat(copy.capacity()).isEqualTo(42);
        assertThat(copy.weight()).isEqualTo(3);
    }
}