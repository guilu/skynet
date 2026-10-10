package dev.skynet.controlplane.definition;

import java.util.List;

/** Un workflow con todas sus versiones, de la más reciente a la más antigua. */
public record WorkflowView(WorkflowSummary workflow, List<VersionView> versions) {}
