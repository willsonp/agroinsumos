package com.agroinsumos.backend.inventario.kardex;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;

/**
 * Mercancía reservada por apartados abiertos (en unidad base: cantidad × factor). Se reserva en el almacén
 * principal, que es de donde sale la factura del apartado; al facturarlo o cancelarlo la reserva se libera sola
 * porque deja de estar ABIERTO. Disponible = existencia − reservado.
 */
@Service
@RequiredArgsConstructor
public class ReservaService {

    private final JdbcTemplate jdbc;

    /** Total reservado del artículo en apartados abiertos. */
    public BigDecimal reservado(Long articuloId) {
        return jdbc.queryForObject("""
                select coalesce(sum(l.cantidad * l.factor), 0) from apartado_linea l
                join apartado ap on ap.id = l.apartado_id
                where ap.estado = 'ABIERTO' and l.articulo_id = ?
                """, BigDecimal.class, articuloId);
    }

    /** Reservado en ese almacén: lo de los apartados si es el principal de su compañía; si no, cero. */
    public BigDecimal reservadoEn(Long articuloId, Long almacenId) {
        return jdbc.queryForObject("""
                select coalesce(sum(l.cantidad * l.factor), 0) from apartado_linea l
                join apartado ap on ap.id = l.apartado_id
                join almacen al on al.compania_id = ap.compania_id and al.principal
                where ap.estado = 'ABIERTO' and l.articulo_id = ? and al.id = ?
                """, BigDecimal.class, articuloId, almacenId);
    }
}
