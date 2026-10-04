package dev.skynet.controlplane.project;

import java.util.List;
import java.util.UUID;
import org.springframework.data.repository.ListCrudRepository;

interface ProjectRepository extends ListCrudRepository<Project, UUID> {

  List<Project> findAllByOrderByKeyAsc();
}
