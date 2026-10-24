/**
 * Memoria de proyecto (hito ME, docs/memoria.md): observaciones al estilo engram (decisiones, bugs
 * resueltos, descubrimientos, convenciones…) de un proyecto o de uno de sus repositorios.
 *
 * <p>Las escriben los agentes mientras trabajan y las personas desde la web; las personas las
 * revisan a posteriori: las editan, las archivan o las fijan. Es estado mutable aparte del
 * historial append-only: cada versión queda en {@code memory_observation_revision} y cada cambio
 * deja un evento {@code memory.*}. Guardar otra vez el mismo {@code topicKey} en el mismo ámbito
 * crea una versión nueva en vez de otra observación. Los secretos se enmascaran al guardar.
 */
package dev.skynet.controlplane.memory;
