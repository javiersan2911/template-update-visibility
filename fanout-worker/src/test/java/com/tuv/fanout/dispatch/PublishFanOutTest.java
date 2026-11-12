package com.tuv.fanout.dispatch;

import static org.assertj.core.api.Assertions.assertThat;

import com.tuv.fanout.domain.EngagementId;
import com.tuv.fanout.domain.FirmId;
import com.tuv.fanout.domain.ItemStatus;
import com.tuv.fanout.domain.VersionPin;
import com.tuv.fanout.port.FanOutSource;
import com.tuv.fanout.port.FanOutSource.Target;
import com.tuv.fanout.support.InMemoryItemStore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class PublishFanOutTest {

    private static final VersionPin PIN = new VersionPin("audit", 7);

    private final InMemoryItemStore store = new InMemoryItemStore();

    private static Stream<Target> targets(int count) {
        return IntStream.range(0, count)
                .mapToObj(i -> new Target(new FirmId("firm-" + i % 3), new EngagementId("e" + i)));
    }

    @Test
    void createsOneItemPerTargetAcrossBatchBoundaries() {
        PublishFanOut fanOut = new PublishFanOut(pin -> targets(25), store, 10);

        assertThat(fanOut.onPublished(PIN)).isEqualTo(25);

        assertThat(store.count(ItemStatus.READY)).isEqualTo(25);
    }

    @Test
    void aRedeliveredPublishEventCreatesNothingNew() {
        PublishFanOut fanOut = new PublishFanOut(pin -> targets(25), store, 10);
        fanOut.onPublished(PIN);

        assertThat(fanOut.onPublished(PIN)).isZero();

        assertThat(store.size()).isEqualTo(25);
    }

    @Test
    void aLaterVersionGetsItsOwnItemsForTheSameEngagements() {
        PublishFanOut fanOut = new PublishFanOut(pin -> targets(5), store, 10);
        fanOut.onPublished(PIN);

        assertThat(fanOut.onPublished(new VersionPin("audit", 8))).isEqualTo(5);
    }

    @Test
    void closesTheSourceStream() {
        AtomicBoolean closed = new AtomicBoolean();
        FanOutSource source = pin -> targets(3).onClose(() -> closed.set(true));

        new PublishFanOut(source, store, 10).onPublished(PIN);

        assertThat(closed).isTrue();
    }

    @Test
    void theSameEngagementIdUnderTwoFirmsGetsTwoItems() {
        EngagementId shared = new EngagementId("e1");
        PublishFanOut fanOut = new PublishFanOut(
                pin -> Stream.of(new Target(new FirmId("firm-a"), shared), new Target(new FirmId("firm-b"), shared)),
                store, 10);

        assertThat(fanOut.onPublished(PIN)).isEqualTo(2);

        assertThat(store.size()).isEqualTo(2);
    }
}
