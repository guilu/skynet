package dev.skynet.runner.verify;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JUnitReportsTest {

  private static final List<String> GLOBS =
      List.of("**/build/test-results/**/*.xml", "**/target/surefire-reports/*.xml");

  @TempDir Path dir;

  @Test
  void readsGradleAndSurefireReportsAndDetailsTheFailures() throws Exception {
    Instant since = Instant.now().minusSeconds(5);
    write(
        "app/build/test-results/test/TEST-CalcTest.xml",
        """
        <?xml version="1.0" encoding="UTF-8"?>
        <testsuite name="CalcTest" tests="3" failures="1" errors="0" skipped="1">
          <testcase classname="CalcTest" name="adds"/>
          <testcase classname="CalcTest" name="subtracts">
            <failure message="expected 3 but was -1" type="AssertionError">at CalcTest.java:12</failure>
          </testcase>
          <testcase classname="CalcTest" name="divides"><skipped/></testcase>
        </testsuite>
        """);
    write(
        "target/surefire-reports/TEST-Other.xml",
        """
        <testsuites>
          <testsuite name="Other">
            <testcase classname="Other" name="boom"><error type="NPE"/></testcase>
            <testcase classname="Other" name="ok"/>
          </testsuite>
        </testsuites>
        """);
    write("build/test-results/test/notes.txt", "no es xml");

    JUnitReports.Summary summary = JUnitReports.read(dir, GLOBS, since);

    assertThat(summary.found()).isTrue();
    assertThat(summary.reports())
        .containsExactly(
            "app/build/test-results/test/TEST-CalcTest.xml",
            "target/surefire-reports/TEST-Other.xml");
    assertThat(summary.total()).isEqualTo(5);
    assertThat(summary.failed()).isEqualTo(1);
    assertThat(summary.errors()).isEqualTo(1);
    assertThat(summary.skipped()).isEqualTo(1);
    assertThat(summary.failures())
        .extracting(f -> f.get("name"), f -> f.get("kind"))
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple("subtracts", "failure"),
            org.assertj.core.groups.Tuple.tuple("boom", "error"));
    assertThat(summary.failures().getFirst())
        .containsEntry("message", "expected 3 but was -1")
        .containsEntry("details", "at CalcTest.java:12");
  }

  @Test
  void ignoresStaleReportsAndCountsBrokenOrHostileOnesAsUnreadable() throws Exception {
    Instant since = Instant.now().minusSeconds(5);
    Path stale =
        write(
            "build/test-results/test/TEST-Old.xml",
            "<testsuite><testcase name=\"old\"><failure/></testcase></testsuite>");
    Files.setLastModifiedTime(stale, FileTime.from(since.minus(1, ChronoUnit.HOURS)));
    write("build/test-results/test/TEST-Broken.xml", "<testsuite><testcase");
    write(
        "build/test-results/test/TEST-Entity.xml",
        """
        <?xml version="1.0"?>
        <!DOCTYPE t [<!ENTITY x SYSTEM "file:///etc/passwd">]>
        <testsuite><testcase name="&x;"/></testsuite>
        """);
    write("node_modules/pkg/build/test-results/TEST-Dep.xml", "<testsuite><testcase/></testsuite>");

    JUnitReports.Summary summary = JUnitReports.read(dir, GLOBS, since);

    assertThat(summary.found()).isFalse();
    assertThat(summary.total()).isZero();
    assertThat(summary.unreadable())
        .containsExactly(
            "build/test-results/test/TEST-Broken.xml", "build/test-results/test/TEST-Entity.xml");
  }

  private Path write(String relative, String content) throws Exception {
    Path file = dir.resolve(relative);
    Files.createDirectories(file.getParent());
    return Files.writeString(file, content);
  }
}
