package dev.skynet.controlplane.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;

class AuthThrottleTest {

  private Instant now = Instant.parse("2026-10-21T10:00:00Z");
  private final Clock clock =
      new Clock() {
        @Override
        public ZoneId getZone() {
          return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
          return this;
        }

        @Override
        public Instant instant() {
          return now;
        }
      };
  private final AuthThrottle throttle = new AuthThrottle(3, Duration.ofMinutes(5), clock);

  @Test
  void blocksAfterTheLastAllowedFailureUntilTheLockoutEnds() {
    throttle.failed("web:1.2.3.4");
    throttle.failed("web:1.2.3.4");
    assertThat(throttle.blocked("web:1.2.3.4")).isEmpty();

    throttle.failed("web:1.2.3.4");
    assertThat(throttle.blocked("web:1.2.3.4")).contains(Duration.ofMinutes(5));
    assertThat(throttle.blocked("web:5.6.7.8")).isEmpty();
    assertThat(throttle.blocked("runner:1.2.3.4")).isEmpty();

    now = now.plus(Duration.ofMinutes(5));
    assertThat(throttle.blocked("web:1.2.3.4")).isEmpty();
  }

  @Test
  void failuresOlderThanALockoutNoLongerCount() {
    throttle.failed("web:1.2.3.4");
    throttle.failed("web:1.2.3.4");
    now = now.plus(Duration.ofMinutes(6));

    throttle.failed("web:1.2.3.4");
    throttle.failed("web:1.2.3.4");
    assertThat(throttle.blocked("web:1.2.3.4")).isEmpty();
  }
}
