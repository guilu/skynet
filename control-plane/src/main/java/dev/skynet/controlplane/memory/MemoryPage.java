package dev.skynet.controlplane.memory;

import java.util.List;

/** Una página de observaciones. */
public record MemoryPage(List<MemoryObservation> items, int page, int size, long total) {}
