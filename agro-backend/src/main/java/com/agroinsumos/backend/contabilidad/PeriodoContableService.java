package com.agroinsumos.backend.contabilidad;

import com.agroinsumos.backend.common.exception.BusinessException;
import com.agroinsumos.backend.security.AuthUser;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** Cierre mensual: un período cerrado no admite asientos (ni manuales ni automáticos). */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PeriodoContableService {

    public record Periodo(int anio, int mes, Instant cerradoEn, Long cerradoPor) {}

    private final JdbcTemplate jdbc;

    public boolean cerrado(Long cia, LocalDate fecha) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "select exists(select 1 from periodo_contable where compania_id = ? and anio = ? and mes = ?)",
                Boolean.class, cia, fecha.getYear(), fecha.getMonthValue()));
    }

    public void exigirAbierto(Long cia, LocalDate fecha) {
        if (cerrado(cia, fecha)) {
            throw new BusinessException("PERIODO_CERRADO", "El período " + fecha.getMonthValue() + "/" + fecha.getYear() + " está cerrado");
        }
    }

    public List<Periodo> cerrados(Long cia) {
        return jdbc.query("select anio, mes, cerrado_en, cerrado_por from periodo_contable where compania_id = ? order by anio desc, mes desc",
                (rs, i) -> new Periodo(rs.getInt(1), rs.getInt(2), rs.getTimestamp(3).toInstant(), (Long) rs.getObject(4)), cia);
    }

    @Transactional
    public void cerrar(AuthUser user, int anio, int mes) {
        validar(mes);
        jdbc.update("insert into periodo_contable (compania_id, anio, mes, cerrado_por) values (?, ?, ?, ?) on conflict do nothing",
                user.companiaId(), anio, mes, user.usuarioId());
    }

    @Transactional
    public void reabrir(AuthUser user, int anio, int mes) {
        validar(mes);
        jdbc.update("delete from periodo_contable where compania_id = ? and anio = ? and mes = ?", user.companiaId(), anio, mes);
    }

    private static void validar(int mes) {
        if (mes < 1 || mes > 12) throw new BusinessException("MES_INVALIDO", "Mes inválido: " + mes);
    }
}
