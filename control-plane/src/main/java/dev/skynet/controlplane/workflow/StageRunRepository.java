package dev.skynet.controlplane.workflow;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.repository.ListCrudRepository;

interface StageRunRepository extends ListCrudRepository<StageRun, UUID> {

  List<StageRun> findByWorkflowRunIdInOrderByCreatedAtAsc(Collection<UUID> workflowRunIds);
}
