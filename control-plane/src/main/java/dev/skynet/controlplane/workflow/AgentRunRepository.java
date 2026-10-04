package dev.skynet.controlplane.workflow;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.repository.ListCrudRepository;

interface AgentRunRepository extends ListCrudRepository<AgentRun, UUID> {

  List<AgentRun> findByStageRunIdInOrderByCreatedAtAsc(Collection<UUID> stageRunIds);
}
