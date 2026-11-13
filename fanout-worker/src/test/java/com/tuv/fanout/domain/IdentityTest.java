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

    static Stream<String> malformedItemIds() {
        return Stream.of(
                "", " ", "a", "a/b", "a/b@1", "a@1/b", "a/b/c", "a/b@x/c", "a/b@/c", "a/b@-1/c", "a/b@+1/c",
                "a/b@01/c", "a/b@1@2/c", "a/b@1/c/d", "/b@1/c", "a/@1/c", "a/b@1/", "a//b@1/c",
                "a/b@99999999999999999999/c", "a/b@9223372036854775808/c", "a/b@\u0661/c",
                "a/b@1/c\n", "a/b@1/c\r\nINFO forged", "a/b@1/c\u0000", "a/b\t@1/c", "a /b@1/c",
                "a/b@1/c\u202e", "a/b@1/caf\u00e9", "a/b@1/c#1",
                "x".repeat(129) + "/b@1/c", "a/" + "x".repeat(129) + "@1/c", "a/b@1/" + "x".repeat(129),
                "a/b@1/c" + " ".repeat(10_000));
    }

    @ParameterizedTest(name = "[{index}]")
    @MethodSource("malformedItemIds")
    void anItemIdMustBeTheJoinedFormOfThreeValidPartsAndNeverEchoesARejectedValue(String value) {
        assertThatIllegalArgumentException().isThrownBy(() -> new ItemId(value))
                .withMessageContaining("item id")
                .satisfies(e -> {
                    if (value.length() > 3) {
                        assertThat(e.getMessage()).doesNotContain(value);
                    }
                });
    }

    @Test
    void aNullItemIdIsRejected() {
        assertThatIllegalArgumentException().isThrownBy(() -> new ItemId(null)).withMessageContaining("item id");
    }

    @Test
    void anItemIdAtTheLongestAllowedPartsAndTheLargestSequenceIsAccepted() {
        String longest = "f".repeat(128) + "/" + "p".repeat(128) + "@" + Long.MAX_VALUE + "/" + "e".repeat(128);

        assertThat(new ItemId(longest).value()).isEqualTo(longest);
        assertThat(ItemId.of(new FirmId("f".repeat(128)), new VersionPin("p".repeat(128), Long.MAX_VALUE),
                new EngagementId("e".repeat(128)))).isEqualTo(new ItemId(longest));
        assertThat(new ItemId("a/b@0/c").value()).isEqualTo("a/b@0/c");
    }

    @Test
    void aVersionPinStillRejectsANegativeSequence() {
        assertThatIllegalArgumentException().isThrownBy(() -> new VersionPin("audit", -1));
    }
}
