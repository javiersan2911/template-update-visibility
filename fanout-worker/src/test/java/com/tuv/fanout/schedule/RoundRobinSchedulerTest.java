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
        assertThat(scheduler.order(Set.of(A, B))).containsExactly(A, B);
        scheduler.served(A);
        scheduler.served(B);

        assertThat(scheduler.order(Set.of(A, B, C))).as("C joined: it is next, ahead of A and B")
                .containsExactly(C, A, B);
    }

    @Test
    void aFirmThatJoinsLaterAndSortsBeforeTheCursorWaitsOnlyForTheFirmsAheadOfIt() {
        scheduler.served(C);

        assertThat(scheduler.order(Set.of(A, C))).containsExactly(A, C);
        assertThat(scheduler.order(Set.of(A, B, C))).as("B joined: behind A, ahead of the just-served C")
                .containsExactly(A, B, C);
    }

    @Test
    void noCandidatesMeansAnEmptyOrderBeforeAndAfterAFirmWasServed() {
        assertThat(scheduler.order(Set.of())).isEmpty();
        scheduler.served(A);

        assertThat(scheduler.order(Set.of())).isEmpty();
    }

    @Test
    void aSingleFirmIsAlwaysNext() {
        assertThat(scheduler.order(Set.of(B))).containsExactly(B);
        scheduler.served(B);

        assertThat(scheduler.order(Set.of(B))).containsExactly(B);
    }

    @Test
    void aLastServedFirmThatIsNoLongerACandidateStillLeavesTheRotationInPlace() {
        scheduler.served(C);
        assertThat(scheduler.order(Set.of(A, B))).as("served firm sorts after every candidate")
                .containsExactly(A, B);

        scheduler.served(A);
        assertThat(scheduler.order(Set.of(B, C))).as("served firm sorts before every candidate")
                .containsExactly(B, C);

        scheduler.served(B);
        assertThat(scheduler.order(Set.of(A, C))).as("served firm sorts between candidates")
                .containsExactly(C, A);
    }
}
