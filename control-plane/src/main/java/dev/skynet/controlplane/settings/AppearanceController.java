package dev.skynet.controlplane.settings;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Paleta de la web. Leerla no exige sesión, para que el login ya salga con los colores elegidos;
 * cambiarla sí.
 */
@RestController
@RequestMapping("/api/settings/appearance")
class AppearanceController {

  private final AppearanceSettings settings;

  AppearanceController(AppearanceSettings settings) {
    this.settings = settings;
  }

  record UpdateAppearance(@NotNull @Valid Palette colors) {}

  @GetMapping
  Appearance get() {
    return settings.get();
  }

  /** Sustituye la paleta entera; con todos los colores vacíos vuelve a la de Skynet. */
  @PutMapping
  Appearance update(@Valid @RequestBody UpdateAppearance request) {
    return settings.save(request.colors());
  }
}
