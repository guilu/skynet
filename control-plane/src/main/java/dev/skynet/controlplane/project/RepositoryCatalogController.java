package dev.skynet.controlplane.project;

import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Catálogo de repositorios de los runners, para el asistente «Importar proyecto» (IP-A). */
@RestController
@RequestMapping("/api/repository-catalog")
class RepositoryCatalogController {

  private final RepositoryCatalogs catalogs;

  RepositoryCatalogController(RepositoryCatalogs catalogs) {
    this.catalogs = catalogs;
  }

  @GetMapping
  List<RepositoryCatalogs.RunnerCatalog> list() {
    return catalogs.list();
  }

  /** «Volver a buscar»: los runners buscan en su siguiente latido y mandan el catálogo nuevo. */
  @PostMapping("/refresh")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  void refresh() {
    catalogs.requestRefresh();
  }
}
