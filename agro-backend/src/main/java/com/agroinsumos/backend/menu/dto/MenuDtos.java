package com.agroinsumos.backend.menu.dto;

import jakarta.validation.constraints.NotNull;

import java.util.List;
import java.util.Set;

public final class MenuDtos {
    private MenuDtos() {}

    public record OpcionResponse(Long id, String codigo, String nombre, String ruta) {}

    public record ModuloResponse(Long id, String codigo, String nombre, List<OpcionResponse> opciones) {}

    public record PermisoOpcionResponse(Long subMenuId, String modulo, String codigo, String nombre, boolean permitido) {}

    public record PermisosRequest(@NotNull Set<Long> subMenuIds) {}
}
