package dev.skynet.controlplane.project;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Las rutas protegidas (VA-A): globs relativos a la raíz del repositorio que ningún agente puede
 * cambiar. Se suman las del repositorio, las del workflow y las de la fase; nunca se quitan.
 */
public final class ProtectedPaths {

  private ProtectedPaths() {}

  /** Una ruta relativa que no sale del repositorio: ni absoluta ni con {@code ..}. */
  public static boolean insideRepository(String value) {
    List<String> segments = Arrays.asList(value.split("[/\\\\]", -1));
    return !value.isBlank()
        && !value.startsWith("/")
        && !value.contains("://")
        && !segments.contains("..")
        && !value.matches("^[A-Za-z]:.*");
  }

  /** La unión, sin repetidos y en orden. */
  @SafeVarargs
  public static List<String> union(List<String>... lists) {
    Set<String> all = new LinkedHashSet<>();
    for (List<String> list : lists) {
      if (list != null) {
        all.addAll(list);
      }
    }
    return List.copyOf(all);
  }
}
