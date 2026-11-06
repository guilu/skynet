package dev.skynet.runner.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import dev.skynet.protocol.runner.RepositoryCatalog;
import dev.skynet.runner.TestRepos;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RepositoryCatalogPublisherTest {

  @TempDir Path dir;

  @Test
  void publishesWhatItFinds() throws Exception {
    TestRepos.create(dir.resolve("forma"));
    List<RepositoryCatalog> sent = new CopyOnWriteArrayList<>();
    try (RepositoryCatalogPublisher publisher =
        new RepositoryCatalogPublisher(
            new RepositoryScanner(List.of(dir), null), null, sent::add)) {
      publisher.publish();
    }
    assertThat(sent).hasSize(1);
    assertThat(sent.getFirst().repositories()).hasSize(1);
  }

  @Test
  void withoutRootsItNeverSendsAnything() {
    List<RepositoryCatalog> sent = new CopyOnWriteArrayList<>();
    try (RepositoryCatalogPublisher publisher =
        new RepositoryCatalogPublisher(new RepositoryScanner(List.of(), null), null, sent::add)) {
      publisher.start();
      publisher.refresh();
      assertThat(publisher.enabled()).isFalse();
    }
    assertThat(sent).isEmpty();
  }
}
