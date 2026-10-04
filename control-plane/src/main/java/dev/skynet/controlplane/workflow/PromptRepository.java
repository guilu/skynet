package dev.skynet.controlplane.workflow;

import java.util.List;
import java.util.UUID;
import org.springframework.data.repository.ListCrudRepository;

interface PromptRepository extends ListCrudRepository<Prompt, UUID> {

  List<Prompt> findByAgentRunIdOrderByCreatedAtAsc(UUID agentRunId);
}
