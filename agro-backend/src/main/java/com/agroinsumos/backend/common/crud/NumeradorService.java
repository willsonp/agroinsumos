package com.agroinsumos.backend.common.crud;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Numeración interna correlativa por compañía (FACTURA, COTIZACION, DEVOLUCION...).
 * El UPSERT bloquea la fila hasta el fin de la transacción: sin huecos ni duplicados
 * (el legacy usaba AUTO_INCREMENT global y "SELECT MAX(id)+1" para mostrar el próximo).
 */
@Service
@RequiredArgsConstructor
public class NumeradorService {

    private final JdbcTemplate jdbc;

    @Transactional(propagation = Propagation.MANDATORY)
    public long siguiente(Long companiaId, String clave) {
        return jdbc.queryForObject("""
                insert into contador_documento(compania_id, clave, ultimo) values (?, ?, 1)
                on conflict (compania_id, clave) do update set ultimo = contador_documento.ultimo + 1
                returning ultimo
                """, Long.class, companiaId, clave);
    }
}
