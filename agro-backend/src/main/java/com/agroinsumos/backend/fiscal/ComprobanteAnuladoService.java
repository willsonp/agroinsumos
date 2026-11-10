package com.agroinsumos.backend.fiscal;

import com.agroinsumos.backend.common.exception.BusinessException;
import com.agroinsumos.backend.common.exception.NotFoundException;
import com.agroinsumos.backend.security.AuthUser;
import jakarta.validation.constraints.*;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Date;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;

/**
 * Registro de NCF anulados para el 608 (legacy: los NCF se anulaban marcando la factura).
 * Un comprobante usado en una factura vigente o un e-CF aceptado no se anula: se emite nota de crédito.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ComprobanteAnuladoService {

    public record Request(@NotBlank @Size(max = 19) String ncf, @NotNull LocalDate fechaComprobante, LocalDate fechaAnulacion,
                          @NotBlank @Pattern(regexp = "0[1-9]|10") String tipoAnulacion, @Size(max = 200) String motivo) {}

    public record Response(Long id, String ncf, LocalDate fechaComprobante, LocalDate fechaAnulacion, String tipoAnulacion,
                           String descripcionTipo, String motivo) {}

    private final JdbcTemplate jdbc;
    private final Clock clock;

    @Transactional
    public Response registrar(AuthUser user, Request r) {
        Long cia = user.companiaId();
        String ncf = r.ncf().trim().toUpperCase();
        if (!ncf.matches("B\\d{10}|E\\d{12}")) throw new BusinessException("NCF_INVALIDO", "NCF inválido: " + r.ncf() + " (B + 10 dígitos o E + 12)");
        LocalDate anulacion = r.fechaAnulacion() != null ? r.fechaAnulacion() : LocalDate.now(clock);
        if (anulacion.isAfter(LocalDate.now(clock)) || r.fechaComprobante().isAfter(anulacion)) {
            throw new BusinessException("FECHA_INVALIDA", "La fecha de anulación no puede ser futura ni anterior al comprobante");
        }
        Long factura = jdbc.query("select id from factura where compania_id = ? and (encf = ? or ncf_legacy = ?) and estado <> 'ANULADA' limit 1",
                rs -> rs.next() ? rs.getLong(1) : null, cia, ncf, ncf);
        if (factura != null) {
            throw new BusinessException("NCF_EN_USO", "El comprobante " + ncf + " corresponde a una factura vigente: emita una nota de crédito");
        }
        String estadoEcf = jdbc.query("select estado from ecf_documento where compania_id = ? and encf = ? limit 1",
                rs -> rs.next() ? rs.getString(1) : null, cia, ncf);
        if (estadoEcf != null && estadoEcf.startsWith("ACEPTADO")) {
            throw new BusinessException("ECF_ACEPTADO", "El e-CF " + ncf + " fue aceptado por la DGII: no se anula, se emite e-CF 34");
        }
        try {
            Long id = jdbc.queryForObject("""
                    insert into comprobante_anulado (compania_id, ncf, fecha_comprobante, fecha_anulacion, tipo_anulacion, motivo, usuario_id)
                    values (?, ?, ?, ?, ?, ?, ?) returning id""", Long.class,
                    cia, ncf, Date.valueOf(r.fechaComprobante()), Date.valueOf(anulacion), r.tipoAnulacion(),
                    r.motivo() == null || r.motivo().isBlank() ? null : r.motivo().trim(), user.usuarioId());
            return obtener(cia, id);
        } catch (DuplicateKeyException e) {
            throw new BusinessException(org.springframework.http.HttpStatus.CONFLICT, "NCF_YA_ANULADO", "El comprobante " + ncf + " ya está registrado como anulado");
        }
    }

    public List<Response> listar(Long cia, LocalDate desde, LocalDate hasta) {
        return jdbc.query("""
                select id, ncf, fecha_comprobante, fecha_anulacion, tipo_anulacion, motivo from comprobante_anulado
                 where compania_id = ? and fecha_anulacion between ? and ? order by fecha_anulacion, ncf""",
                (rs, i) -> new Response(rs.getLong(1), rs.getString(2), rs.getDate(3).toLocalDate(), rs.getDate(4).toLocalDate(),
                        rs.getString(5), Formato608.TIPOS.get(rs.getString(5)), rs.getString(6)),
                cia, Date.valueOf(desde), Date.valueOf(hasta));
    }

    @Transactional
    public void eliminar(Long cia, Long id) {
        if (jdbc.update("delete from comprobante_anulado where compania_id = ? and id = ?", cia, id) == 0) {
            throw new NotFoundException("Comprobante anulado", id);
        }
    }

    private Response obtener(Long cia, Long id) {
        return jdbc.queryForObject("select id, ncf, fecha_comprobante, fecha_anulacion, tipo_anulacion, motivo from comprobante_anulado where compania_id = ? and id = ?",
                (rs, i) -> new Response(rs.getLong(1), rs.getString(2), rs.getDate(3).toLocalDate(), rs.getDate(4).toLocalDate(),
                        rs.getString(5), Formato608.TIPOS.get(rs.getString(5)), rs.getString(6)), cia, id);
    }
}
