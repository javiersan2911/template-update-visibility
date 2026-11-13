package com.tuv.fanout.port;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import com.tuv.fanout.domain.AttemptId;
import com.tuv.fanout.domain.EngagementId;
import com.tuv.fanout.domain.FirmId;
import com.tuv.fanout.domain.VersionPin;
import com.tuv.fanout.domain.WorkItem;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class ApplyCommandTest {

    private static final AttemptId ATTEMPT = new AttemptId("firm-a/audit@7/e1#1");
    private static final FirmId FIRM = new FirmId("firm-a");
    private static final EngagementId ENGAGEMENT = new EngagementId("e1");
    private static final VersionPin PIN = new VersionPin("audit", 7);

    static Stream<Arguments> nullComponents() {
        return Stream.of(
                Arguments.of("attemptId", (Runnable) () -> new ApplyCommand(null, FIRM, ENGAGEMENT, PIN)),
                Arguments.of("firmId", (Runnable) () -> new ApplyCommand(ATTEMPT, null, ENGAGEMENT, PIN)),
                Arguments.of("engagementId", (Runnable) () -> new ApplyCommand(ATTEMPT, FIRM, null, PIN)),
                Arguments.of("pin", (Runnable) () -> new ApplyCommand(ATTEMPT, FIRM, ENGAGEMENT, null)));
    }

    @ParameterizedTest(name = "{0} is null")
    @MethodSource("nullComponents")
    void rejectsANullComponentNamingIt(String component, Runnable construct) {
        assertThatNullPointerException().isThrownBy(construct::run).withMessageContaining(component);
    }

    @Test
    void aCommandForAnItemCarriesTheItemsKeyAndIdentity() {
        WorkItem item = WorkItem.create(FIRM, ENGAGEMENT, PIN);

        ApplyCommand command = ApplyCommand.forItem(item);

        assertThat(command.attemptId()).isEqualTo(item.attemptId());
        assertThat(command.firmId()).isEqualTo(FIRM);
        assertThat(command.engagementId()).isEqualTo(ENGAGEMENT);
        assertThat(command.pin()).isEqualTo(PIN);
    }
}
