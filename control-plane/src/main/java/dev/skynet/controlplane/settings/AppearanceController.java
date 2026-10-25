package dev.skynet.controlplane.settings;

import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Apariencia de la web: la paleta y el paquete de iconos. Leerla no exige sesión, para que el login
 * ya salga con lo elegido; cambiarla sí.
 */
@RestController
@RequestMapping("/api/settings/appearance")
class AppearanceController {

  private final AppearanceSettings settings;

  AppearanceController(AppearanceSettings settings) {
    this.settings = settings;
  }

  record UpdateAppearance(@Valid Palette colors, IconPack icons) {}

  @GetMapping
  Appearance get() {
    return settings.get();
  }

  /**
   * Cambia lo que llegue. La paleta se sustituye entera (con todos los colores vacíos vuelve a la
   * de Skynet); sin {@code colors} o sin {@code icons}, esa parte se queda como estaba.
   */
  @PutMapping
  Appearance update(@Valid @RequestBody UpdateAppearance request) {
    return settings.save(request.colors(), request.icons());
  }
}
