package com.tuv.fanout.port;

import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import com.tuv.fanout.domain.EngagementId;
import com.tuv.fanout.domain.FirmId;
import com.tuv.fanout.port.FanOutSource.Target;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class FanOutSourceTargetTest {

    static Stream<Arguments> nullComponents() {
        return Stream.of(
                Arguments.of("firmId", (Runnable) () -> new Target(null, new EngagementId("e1"))),
                Arguments.of("engagementId", (Runnable) () -> new Target(new FirmId("firm-a"), null)));
    }

    @ParameterizedTest(name = "{0} is null")
    @MethodSource("nullComponents")
    void rejectsANullComponentNamingIt(String component, Runnable construct) {
        assertThatNullPointerException().isThrownBy(construct::run).withMessageContaining(component);
    }
}
