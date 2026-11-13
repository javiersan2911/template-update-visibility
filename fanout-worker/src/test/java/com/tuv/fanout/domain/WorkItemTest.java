package com.tuv.fanout.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

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

    @Test
    void anIdThatDoesNotNameTheItemsOwnFirmPinAndEngagementIsRejected() {
        FirmId otherFirm = new FirmId("firm-b");
        EngagementId otherEngagement = new EngagementId("e2");
        VersionPin otherPin = new VersionPin("audit", 8);

        assertThatIllegalArgumentException().as("firm A's id on a firm B item").isThrownBy(
                () -> new WorkItem(ItemId.of(FIRM, PIN, ENGAGEMENT), otherFirm, ENGAGEMENT, PIN, 1, 0, 0));
        assertThatIllegalArgumentException().as("another engagement").isThrownBy(
                () -> new WorkItem(ItemId.of(FIRM, PIN, ENGAGEMENT), FIRM, otherEngagement, PIN, 1, 0, 0));
        assertThatIllegalArgumentException().as("another version").isThrownBy(
                () -> new WorkItem(ItemId.of(FIRM, PIN, ENGAGEMENT), FIRM, ENGAGEMENT, otherPin, 1, 0, 0));
    }

    @Test
    void theMessageForAMismatchedIdDoesNotEchoTheValues() {
        FirmId otherFirm = new FirmId("firm-hostile-b");

        assertThatIllegalArgumentException().isThrownBy(
                        () -> new WorkItem(ItemId.of(FIRM, PIN, ENGAGEMENT), otherFirm, ENGAGEMENT, PIN, 1, 0, 0))
                .satisfies(e -> assertThat(e.getMessage())
                        .doesNotContain("firm-hostile-b").doesNotContain("firm-a").doesNotContain("e1"));
    }

    @Test
    void anIdThatMatchesIsAccepted() {
        assertThat(new WorkItem(ItemId.of(FIRM, PIN, ENGAGEMENT), FIRM, ENGAGEMENT, PIN, 1, 0, 0).id())
                .isEqualTo(ItemId.of(FIRM, PIN, ENGAGEMENT));
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(value = RequeueReason.class, names = {"CAPACITY_DEFERRED", "NOT_ATTEMPTED"})
    void anItemThatWasNeverLeasedCannotBeRequeuedWithARefund(RequeueReason refundingReason) {
        WorkItem neverLeased = WorkItem.create(FIRM, ENGAGEMENT, PIN);

        assertThatIllegalStateException().isThrownBy(() -> neverLeased.requeued(refundingReason))
                .withMessageContaining("never leased");
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(value = RequeueReason.class, names = {"CAPACITY_DEFERRED", "NOT_ATTEMPTED"})
    void aLeasedItemCanBeRequeuedWithARefund(RequeueReason refundingReason) {
        assertThat(WorkItem.create(FIRM, ENGAGEMENT, PIN).delivered().requeued(refundingReason).deliveryCount())
                .isZero();
    }
}
