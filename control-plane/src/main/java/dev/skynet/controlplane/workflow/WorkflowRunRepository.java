package dev.skynet.controlplane.workflow;

import java.util.List;
import java.util.UUID;
import org.springframework.data.repository.ListCrudRepository;

interface WorkflowRunRepository extends ListCrudRepository<WorkflowRun, UUID> {

  List<WorkflowRun> findByWorkItemIdOrderByCreatedAtDesc(UUID workItemId);
}
