package com.tuv.fanout.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.util.function.Function;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** Ids are joined into item ids and logged, so they are restricted to a safe, unambiguous alphabet. */
class IdentityTest {

    private static final FirmId FIRM = new FirmId("firm-a");
    private static final VersionPin PIN = new VersionPin("audit", 7);

    private static final Function<String, Object> FIRM_ID = FirmId::new;
    private static final Function<String, Object> ENGAGEMENT_ID = EngagementId::new;
    private static final Function<String, Object> PRODUCT_ID = product -> new VersionPin(product, 1);

    static Stream<Arguments> badValuesForEveryIdKind() {
        String[] bad = {
            "a/b", "a@b", "a#b", "a b", " a", "a ", "a\nb", "a\n", "a\tb", "a\u0000b", "", " ", "x".repeat(129),
            "caf\u00e9", "a\u202eb", "a,b", "a;b", "a'b", "a%2Fb"
        };
        Stream.Builder<Arguments> arguments = Stream.builder();
        for (String value : bad) {
            arguments.add(Arguments.of("firm id", FIRM_ID, value));
            arguments.add(Arguments.of("engagement id", ENGAGEMENT_ID, value));
            arguments.add(Arguments.of("product id", PRODUCT_ID, value));
        }
        for (var kind : new Object[][] {{"firm id", FIRM_ID}, {"engagement id", ENGAGEMENT_ID}, {"product id", PRODUCT_ID}}) {
            arguments.add(Arguments.of(kind[0], kind[1], null));
        }
        return arguments.build();
    }

    static Stream<Arguments> goodValuesForEveryIdKind() {
        return Stream.of("a", "A-b_c.9", "firm-0", "x".repeat(128), "0", "v1.2.3")
                .flatMap(value -> Stream.of(
                        Arguments.of(FIRM_ID, value), Arguments.of(ENGAGEMENT_ID, value), Arguments.of(PRODUCT_ID, value)));
    }

    @ParameterizedTest(name = "{0} rejects {2}")
    @MethodSource("badValuesForEveryIdKind")
    void rejectsValuesOutsideTheAllowedAlphabetOrLength(String kind, Function<String, Object> create, String value) {
        assertThatIllegalArgumentException().isThrownBy(() -> create.apply(value))
                .withMessageContaining(kind)
                .satisfies(e -> {
                    if (value != null && value.length() > 1) {
                        assertThat(e.getMessage()).as("the rejected value is not echoed into logs").doesNotContain(value);
                    }
                });
    }

    @ParameterizedTest
    @MethodSource("goodValuesForEveryIdKind")
    void acceptsLettersDigitsDotUnderscoreAndHyphenUpTo128Characters(Function<String, Object> create, String value) {
        assertThat(create.apply(value)).isNotNull();
    }

    @Test
    void anItemIdNamesTheFirmTheProductVersionAndTheEngagement() {
        assertThat(ItemId.of(FIRM, PIN, new EngagementId("e1")).value()).isEqualTo("firm-a/audit@7/e1");
        assertThat(WorkItem.create(FIRM, new EngagementId("e1"), PIN).id().value()).isEqualTo("firm-a/audit@7/e1");
    }

    @Test
    void theSameEngagementIdUnderTwoFirmsIsTwoDistinctItems() {
        EngagementId engagement = new EngagementId("e1");

        ItemId first = ItemId.of(new FirmId("firm-a"), PIN, engagement);
        ItemId second = ItemId.of(new FirmId("firm-b"), PIN, engagement);

        assertThat(first).isNotEqualTo(second);
        assertThat(WorkItem.create(new FirmId("firm-a"), engagement, PIN).attemptId())
                .isNotEqualTo(WorkItem.create(new FirmId("firm-b"), engagement, PIN).attemptId());
    }

    @Test
    void theTwoTuplesThatUsedToCollideCanNoLongerBeConstructed() {
        // ("a", 1, "b@2/c") and ("a@1/b", 2, "c") both joined to "a@1/b@2/c".
        assertThatIllegalArgumentException().isThrownBy(
                () -> ItemId.of(FIRM, new VersionPin("a", 1), new EngagementId("b@2/c")));
        assertThatIllegalArgumentException().isThrownBy(
                () -> ItemId.of(FIRM, new VersionPin("a@1/b", 2), new EngagementId("c")));
    }

    @Test
    void aVersionPinStillRejectsANegativeSequence() {
        assertThatIllegalArgumentException().isThrownBy(() -> new VersionPin("audit", -1));
    }
}
