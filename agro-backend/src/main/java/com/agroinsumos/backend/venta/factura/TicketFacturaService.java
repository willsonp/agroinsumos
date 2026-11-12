package com.agroinsumos.backend.venta.factura;

import com.agroinsumos.backend.common.exception.NotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.LocalDateTime;

/**
 * Datos del ticket POS que no están en la factura: contacto del cliente, quién atendió, fecha de firma del e-CF y
 * cuántas veces se ha impreso (la primera es "Original"; las siguientes, "Reimpresión").
 */
@Service
@RequiredArgsConstructor
public class TicketFacturaService {

    public record TicketResponse(Long clienteId, String clienteTelefono, String clienteDireccion, String atendio,
                                 LocalDateTime fechaFirma, int impresiones) {}

    public record ImpresionResponse(int impresiones) {}

    private final JdbcTemplate jdbc;

    @Transactional(readOnly = true)
    public TicketResponse datos(Long cia, Long facturaId) {
        return jdbc.query("""
                select f.cliente_id, coalesce(nullif(c.telefono, ''), c.celular) telefono, c.direccion, u.username,
                       e.fecha_hora_firma, f.impresiones
                  from factura f
                  left join cliente c on c.id = f.cliente_id
                  left join usuario u on u.id = f.usuario_id
                  left join ecf_documento e on e.id = f.ecf_documento_id
                 where f.id = ? and f.compania_id = ?""", rs -> {
            if (!rs.next()) throw new NotFoundException("Factura", facturaId);
            Timestamp firma = rs.getTimestamp("fecha_hora_firma");
            return new TicketResponse(rs.getObject("cliente_id", Long.class), rs.getString("telefono"), rs.getString("direccion"),
                    rs.getString("username"), firma == null ? null : firma.toLocalDateTime(), rs.getInt("impresiones"));
        }, facturaId, cia);
    }

    /** Registra una impresión y devuelve el total. */
    @Transactional
    public int registrarImpresion(Long cia, Long facturaId) {
        Integer n = jdbc.query("update factura set impresiones = impresiones + 1 where id = ? and compania_id = ? returning impresiones",
                rs -> rs.next() ? rs.getInt(1) : null, facturaId, cia);
        if (n == null) throw new NotFoundException("Factura", facturaId);
        return n;
    }
}
