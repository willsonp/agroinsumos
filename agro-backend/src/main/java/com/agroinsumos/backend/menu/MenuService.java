package com.agroinsumos.backend.menu;

import com.agroinsumos.backend.common.exception.BusinessException;
import com.agroinsumos.backend.menu.dto.MenuDtos.*;
import com.agroinsumos.backend.usuario.Nivel;
import com.agroinsumos.backend.usuario.Usuario;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Árbol de menú por usuario (antes: JTree poblado en MenuPrincipal con dos CALL anidados,
 * un patrón N+1). Ahora: una sola consulta y el frontend decide cómo dibujarlo.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MenuService {

    private final SubMenuRepository subMenus;
    private final OpcionMenuUsuarioRepository opciones;

    public List<ModuloResponse> menuDe(Long usuarioId, Nivel nivel) {
        // El administrador ve todo, como en el legacy (if "A".equals(nivel)).
        List<SubMenu> visibles = nivel == Nivel.ADMIN ? subMenus.findActivos() : opciones.opcionesPermitidas(usuarioId);
        Map<MainMenu, List<SubMenu>> porModulo = visibles.stream()
                .collect(Collectors.groupingBy(SubMenu::getMainMenu, LinkedHashMap::new, Collectors.toList()));
        return porModulo.entrySet().stream()
                .map(e -> new ModuloResponse(e.getKey().getId(), e.getKey().getCodigo(), e.getKey().getNombreModulo(),
                        e.getValue().stream()
                                .map(s -> new OpcionResponse(s.getId(), s.getCodigo(), s.getNombreOpcion(), s.getRutaFrontend()))
                                .toList()))
                .toList();
    }

    /** Códigos de opción permitidos: base para autorización fina (@PreAuthorize con permisos). */
    public Set<String> codigosPermitidos(Long usuarioId) {
        return opciones.opcionesPermitidas(usuarioId).stream().map(SubMenu::getCodigo).collect(Collectors.toSet());
    }

    public List<PermisoOpcionResponse> permisosDe(Long usuarioId) {
        Set<Long> permitidas = opciones.opcionesPermitidas(usuarioId).stream().map(SubMenu::getId).collect(Collectors.toSet());
        return subMenus.findActivos().stream()
                .map(s -> new PermisoOpcionResponse(s.getId(), s.getMainMenu().getNombreModulo(), s.getCodigo(),
                        s.getNombreOpcion(), permitidas.contains(s.getId())))
                .toList();
    }

    @Transactional
    public void asignar(Usuario usuario, Set<Long> subMenuIds) {
        List<SubMenu> seleccion = subMenus.findAllById(subMenuIds);
        if (seleccion.size() != subMenuIds.size()) {
            throw new BusinessException("OPCION_INVALIDA", "Una o más opciones de menú no existen");
        }
        opciones.eliminarDeUsuario(usuario.getId());
        opciones.flush();
        opciones.saveAll(seleccion.stream().map(s -> new OpcionMenuUsuario(s, usuario)).toList());
    }
}
