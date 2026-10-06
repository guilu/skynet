package dev.skynet.runner.supervisor;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * Grupos de procesos leídos de {@code /proc} (Linux). Un agente lanzado con {@code setsid} lidera
 * su propio grupo, y sus descendientes siguen en él aunque se desenganchen del árbol (un proceso en
 * segundo plano cuyo padre ya terminó), así que matar el grupo no deja huérfanos. Donde no hay
 * {@code /proc} no se conoce ningún grupo y se recurre solo al árbol de procesos.
 */
final class ProcessGroups {

  private static final Path PROC = Path.of("/", "proc");

  private ProcessGroups() {}

  /** El proceso lidera su grupo: se lanzó con {@code setsid}. */
  static boolean isLeader(long pid) {
    return groupOf(pid).orElse(-1) == pid;
  }

  /** Procesos vivos del grupo {@code pgid}, incluido su líder si sigue vivo. */
  static List<ProcessHandle> members(long pgid) {
    return ProcessHandle.allProcesses()
        .filter(h -> groupOf(h.pid()).orElse(-1) == pgid)
        .filter(ProcessGroups::isRunning)
        .toList();
  }

  /**
   * Vivo y no zombi. Un proceso muerto cuyo padre aún no lo ha recogido sigue contando como vivo
   * para {@link ProcessHandle#isAlive()}; pasa con los huérfanos cuando el pid 1 de un contenedor
   * no recoge a sus hijos adoptivos.
   */
  static boolean isRunning(ProcessHandle process) {
    return process.isAlive() && stat(process.pid()).map(f -> !"Z".equals(f[0])).orElse(true);
  }

  static OptionalLong groupOf(long pid) {
    return stat(pid).map(f -> OptionalLong.of(Long.parseLong(f[2]))).orElse(OptionalLong.empty());
  }

  /** Campos de {@code /proc/<pid>/stat} a partir del estado: estado, ppid, pgrp... */
  private static Optional<String[]> stat(long pid) {
    try {
      String stat = Files.readString(PROC.resolve(Long.toString(pid)).resolve("stat"));
      // "pid (comando) estado ppid pgrp ...": el comando puede contener espacios y paréntesis.
      return Optional.of(stat.substring(stat.lastIndexOf(')') + 2).split(" "));
    } catch (IOException | RuntimeException e) {
      return Optional.empty();
    }
  }
}
