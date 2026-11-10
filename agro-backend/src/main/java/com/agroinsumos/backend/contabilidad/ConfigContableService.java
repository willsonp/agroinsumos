package com.agroinsumos.backend.contabilidad;

import com.agroinsumos.backend.common.exception.BusinessException;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/** Cuentas por defecto de los asientos automáticos (sustituye compania.Cuenta*). */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ConfigContableService {

    public record Item(ClaveContable clave, String descripcion, String codigo, String nombreCuenta) {}

    public record Cambio(ClaveContable clave, @Size(max = 14) String codigo) {}

    /** Documentos con fecha anterior no se contabilizan (su contabilidad viene del sistema anterior). */
    public record Inicio(java.time.LocalDate fechaInicio) {}

    private final JdbcTemplate jdbc;
    private final CuentaContableRepository cuentas;

    public Map<ClaveContable, String> mapa(Long cia) {
        Map<ClaveContable, String> m = new EnumMap<>(ClaveContable.class);
        jdbc.query("select clave, codigo from cuenta_contable_config where compania_id = ?", rs -> {
            try { m.put(ClaveContable.valueOf(rs.getString(1)), rs.getString(2)); } catch (IllegalArgumentException ignorada) { /* clave obsoleta */ }
        }, cia);
        return m;
    }

    public List<Item> listar(Long cia) {
        var m = mapa(cia);
        return Arrays.stream(ClaveContable.values()).map(k -> {
            String codigo = m.get(k);
            String nombre = codigo == null ? null : cuentas.findByCompaniaIdAndCodigo(cia, codigo).map(CuentaContable::getNombre).orElse(null);
            return new Item(k, k.descripcion(), codigo, nombre);
        }).toList();
    }

    public Inicio inicio(Long cia) {
        return new Inicio(jdbc.queryForObject("select fecha_inicio_contable from compania where id = ?", java.time.LocalDate.class, cia));
    }

    @Transactional
    public Inicio fijarInicio(Long cia, Inicio inicio) {
        jdbc.update("update compania set fecha_inicio_contable = ? where id = ?",
                inicio.fechaInicio() == null ? null : java.sql.Date.valueOf(inicio.fechaInicio()), cia);
        return inicio(cia);
    }

    @Transactional
    public List<Item> guardar(Long cia, List<Cambio> cambios) {
        for (var c : cambios) {
            if (c.clave() == null) continue;
            if (c.codigo() == null || c.codigo().isBlank()) {
                jdbc.update("delete from cuenta_contable_config where compania_id = ? and clave = ?", cia, c.clave().name());
                continue;
            }
            var cuenta = cuentas.findByCompaniaIdAndCodigo(cia, c.codigo().trim())
                    .orElseThrow(() -> new BusinessException("CUENTA_INEXISTENTE", "No existe la cuenta " + c.codigo()));
            if (!cuenta.isAceptaMovimiento() || !"A".equals(cuenta.getEstado())) {
                throw new BusinessException("CUENTA_NO_ACEPTA_MOVIMIENTO", "La cuenta " + cuenta.getCodigo() + " es de grupo o está inactiva");
            }
            jdbc.update("""
                    insert into cuenta_contable_config (compania_id, clave, codigo) values (?, ?, ?)
                    on conflict (compania_id, clave) do update set codigo = excluded.codigo""", cia, c.clave().name(), cuenta.getCodigo());
        }
        return listar(cia);
    }
}
