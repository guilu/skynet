package dev.skynet.controlplane.workitem;

import java.util.List;
import java.util.UUID;
import org.springframework.data.repository.ListCrudRepository;

interface WorkItemRepository extends ListCrudRepository<WorkItem, UUID> {

  List<WorkItem> findByProjectIdOrderByNumberDesc(UUID projectId);
}
