package dev.skynet.controlplane.workflow;

import java.security.Principal;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Las lecciones propuestas de los hallazgos (AP-B): verlas, aplicarlas o descartarlas. */
@RestController
class LessonController {

  private final Lessons lessons;

  LessonController(Lessons lessons) {
    this.lessons = lessons;
  }

  @GetMapping("/api/projects/{id}/lessons")
  List<Lessons.LessonView> ofProject(
      @PathVariable UUID id, @RequestParam(required = false) String status) {
    return lessons.ofProject(id, status);
  }

  @GetMapping("/api/lessons/{id}")
  Lessons.LessonDetail detail(@PathVariable UUID id) {
    return lessons.detail(id);
  }

  /** Aplica la lección, con el texto editado o, si no lo hay, con el propuesto. */
  @PostMapping("/api/lessons/{id}/apply")
  Lessons.LessonView apply(
      @PathVariable UUID id,
      @RequestBody(required = false) ApplyRequest request,
      Principal principal) {
    return lessons.apply(id, request == null ? null : request.text(), actor(principal));
  }

  @PostMapping("/api/lessons/{id}/dismiss")
  Lessons.LessonView dismiss(
      @PathVariable UUID id,
      @RequestBody(required = false) DismissRequest request,
      Principal principal) {
    return lessons.dismiss(id, request == null ? null : request.reason(), actor(principal));
  }

  private static String actor(Principal principal) {
    return principal == null ? "anónimo" : principal.getName();
  }

  record ApplyRequest(String text) {}

  record DismissRequest(String reason) {}
}
