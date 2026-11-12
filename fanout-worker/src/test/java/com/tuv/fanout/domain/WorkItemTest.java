package com.tuv.fanout.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import org.junit.jupiter.api.Test;

class WorkItemTest {

    private static final FirmId FIRM = new FirmId("firm-a");
    private static final EngagementId ENGAGEMENT = new EngagementId("e1");
    private static final VersionPin PIN = new VersionPin("audit", 7);

    private static WorkItem leasedTwiceAfterTwoDeferrals() {
        return WorkItem.create(FIRM, ENGAGEMENT, PIN).delivered()
                .requeued(RequeueReason.CAPACITY_DEFERRED).delivered()
                .requeued(RequeueReason.NOT_ATTEMPTED).delivered();
    }

    @Test
    void aNewItemHasBeenDeferredNever() {
        assertThat(WorkItem.create(FIRM, ENGAGEMENT, PIN).deferrals()).isZero();
    }

    @Test
    void bothRefundedReasonsCountAsADeferral() {
        WorkItem item = leasedTwiceAfterTwoDeferrals();

        assertThat(item.deferrals()).isEqualTo(2);
        assertThat(item.deliveryCount()).as("each deferral refunded its delivery").isEqualTo(1);
        assertThat(item.attemptNumber()).isEqualTo(1);
    }

    @Test
    void aRealAttemptResetsTheDeferralCount() {
        assertThat(leasedTwiceAfterTwoDeferrals().requeued(RequeueReason.OUTCOME_UNKNOWN).deferrals()).isZero();
        assertThat(leasedTwiceAfterTwoDeferrals().requeued(RequeueReason.ATTEMPT_FAILED).deferrals()).isZero();
    }

    @Test
    void aHandBackLeavesTheDeferralCountAlone() {
        assertThat(leasedTwiceAfterTwoDeferrals().requeued(RequeueReason.HANDED_BACK).deferrals()).isEqualTo(2);
    }

    @Test
    void deferralsCannotBeNegative() {
        assertThatIllegalArgumentException().isThrownBy(
                () -> new WorkItem(ItemId.of(FIRM, PIN, ENGAGEMENT), FIRM, ENGAGEMENT, PIN, 1, 0, -1));
    }
}
