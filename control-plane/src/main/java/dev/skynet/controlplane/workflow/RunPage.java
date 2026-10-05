package dev.skynet.controlplane.workflow;

import java.util.List;

/** Página de ejecuciones, de más reciente a más antigua. */
public record RunPage(List<RunView> items, int page, int size, long total) {}
