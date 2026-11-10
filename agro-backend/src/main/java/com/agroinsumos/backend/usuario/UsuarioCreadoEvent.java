package com.agroinsumos.backend.usuario;

/**
 * Publicado al crear/editar un usuario. El legacy llamaba directamente a
 * "call agrega_vendedor(...)" desde Ctrl_Usuario.Guardar(); aquí el módulo de
 * Vendedores escuchará este evento (@TransactionalEventListener) cuando se migre,
 * evitando acoplar Usuario -> Vendedor (principio de inversión de dependencias).
 */
public record UsuarioCreadoEvent(Long usuarioId, Long companiaId, String nombre, String cedula,
                                 String direccion, String correo) {
}
