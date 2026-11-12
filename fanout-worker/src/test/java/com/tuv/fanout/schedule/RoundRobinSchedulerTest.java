package com.tuv.fanout.schedule;

import static org.assertj.core.api.Assertions.assertThat;

import com.tuv.fanout.domain.FirmId;
import java.util.Set;
import org.junit.jupiter.api.Test;

class RoundRobinSchedulerTest {

    private static final FirmId A = new FirmId("a");
    private static final FirmId B = new FirmId("b");
    private static final FirmId C = new FirmId("c");

    private final RoundRobinScheduler scheduler = new RoundRobinScheduler();

    @Test
    void startsInAStableOrder() {
        assertThat(scheduler.order(Set.of(C, A, B))).containsExactly(A, B, C);
    }

    @Test
    void theFirmJustServedGoesToTheBackOfTheLine() {
        scheduler.served(A);

        assertThat(scheduler.order(Set.of(A, B, C))).containsExactly(B, C, A);
    }

    @Test
    void keepsRotatingWhenTheLastServedFirmHasNoWorkLeft() {
        scheduler.served(B);

        assertThat(scheduler.order(Set.of(A, C))).containsExactly(C, A);
    }

    @Test
    void aFirmThatJoinsLaterIsNotPutBehindTheWholeQueue() {
        scheduler.served(C);

        assertThat(scheduler.order(Set.of(A, B, C))).containsExactly(A, B, C);
    }
}
