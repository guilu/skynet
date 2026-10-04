package dev.skynet.controlplane.project;

import java.util.List;
import java.util.UUID;
import org.springframework.data.repository.ListCrudRepository;

interface CodeRepositoryRepository extends ListCrudRepository<CodeRepository, UUID> {

  List<CodeRepository> findByProjectIdOrderByNameAsc(UUID projectId);
}
