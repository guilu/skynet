package dev.skynet.controlplane.workflow;

import java.time.Instant;
import java.util.UUID;

record PromptView(UUID id, String role, String content, String sha256, Instant createdAt) {

  static PromptView of(Prompt p) {
    return new PromptView(p.getId(), p.getRole(), p.getContent(), p.getSha256(), p.getCreatedAt());
  }
}
