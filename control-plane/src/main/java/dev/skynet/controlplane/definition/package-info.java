/**
 * Definiciones de workflow (W1): el YAML de §11, su validación y sus versiones.
 *
 * <p>Un workflow se identifica por su clave ({@code id} en el YAML) y tiene versiones numeradas.
 * Una versión empieza como borrador, que se puede editar y guardar aunque tenga errores; se
 * considera validada cuando no tiene ninguno, y al publicarla queda congelada. Editar un workflow
 * publicado abre un borrador de la versión siguiente.
 */
package dev.skynet.controlplane.definition;
