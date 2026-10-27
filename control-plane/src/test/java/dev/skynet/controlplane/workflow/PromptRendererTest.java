package dev.skynet.controlplane.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.skynet.controlplane.project.Project;
import dev.skynet.controlplane.workitem.WorkItem;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Las variables del trabajo y su proyecto en un prompt. */
class PromptRendererTest {

  @Test
  void projectSlugIsTheKeyInLowerCase() {
    Project project = mock(Project.class);
    when(project.getKey()).thenReturn("DEMO2");
    when(project.getName()).thenReturn("Demo repo");
    WorkItem workItem = mock(WorkItem.class);
    when(workItem.getKey()).thenReturn("DEMO2-7");

    String prompt =
        new PromptRenderer(workItem, project, Map.of())
            .render(
                "Escribe docs/skynet/mapa-{{project.slug}}.md (proyecto {{project.name}},"
                    + " {{project.key}}) para {{ workItem.key }}.");

    assertThat(prompt)
        .isEqualTo("Escribe docs/skynet/mapa-demo2.md (proyecto Demo repo, DEMO2) para DEMO2-7.");
  }
}
