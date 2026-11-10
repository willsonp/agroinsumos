package com.agroinsumos.backend.moneda;

import com.agroinsumos.backend.common.exception.BusinessException;
import com.agroinsumos.backend.common.exception.NotFoundException;
import com.agroinsumos.backend.security.AuthUser;
import jakarta.validation.constraints.*;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Date;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;

/**
 * Tasas de cambio (pesos por unidad de moneda extranjera). La tasa de una fecha es la última registrada
 * en esa fecha o antes (normalmente la tasa del Banco Central del día).
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class TasaCambioService {

    public static final String PESO = "DOP";

    public record Request(@NotBlank @Pattern(regexp = "[A-Z]{3}") String moneda, LocalDate fecha,
                          @NotNull @DecimalMin("0.0001") @Digits(integer = 8, fraction = 4) BigDecimal tasa) {}

    public record Tasa(String moneda, LocalDate fecha, BigDecimal tasa) {}

    private final JdbcTemplate jdbc;
    private final Clock clock;

    @Transactional
    public Tasa registrar(AuthUser user, Request r) {
        if (PESO.equals(r.moneda())) throw new BusinessException("MONEDA_INVALIDA", "El peso dominicano es la moneda base (tasa 1)");
        LocalDate fecha = r.fecha() != null ? r.fecha() : LocalDate.now(clock);
        if (fecha.isAfter(LocalDate.now(clock))) throw new BusinessException("FECHA_FUTURA", "La fecha no puede ser futura");
        jdbc.update("""
                insert into tasa_cambio (compania_id, moneda, fecha, tasa, usuario_id) values (?, ?, ?, ?, ?)
                on conflict (compania_id, moneda, fecha) do update set tasa = excluded.tasa, usuario_id = excluded.usuario_id""",
                user.companiaId(), r.moneda(), Date.valueOf(fecha), r.tasa(), user.usuarioId());
        return new Tasa(r.moneda(), fecha, r.tasa().setScale(4, RoundingMode.HALF_UP));
    }

    public List<Tasa> listar(Long cia, String moneda) {
        return jdbc.query("""
                select moneda, fecha, tasa from tasa_cambio where compania_id = ? and (cast(? as varchar) is null or moneda = ?)
                 order by fecha desc, moneda limit 200""",
                (rs, i) -> new Tasa(rs.getString(1), rs.getDate(2).toLocalDate(), rs.getBigDecimal(3)), cia, moneda, moneda);
    }

    /** Tasa vigente a la fecha; error si la moneda no tiene tasa registrada. */
    public Tasa vigente(Long cia, String moneda, LocalDate fecha) {
        if (PESO.equals(moneda)) return new Tasa(PESO, fecha, BigDecimal.ONE);
        return jdbc.query("""
                select moneda, fecha, tasa from tasa_cambio where compania_id = ? and moneda = ? and fecha <= ?
                 order by fecha desc limit 1""",
                rs -> rs.next() ? new Tasa(rs.getString(1), rs.getDate(2).toLocalDate(), rs.getBigDecimal(3)) : null,
                cia, moneda, Date.valueOf(fecha)) instanceof Tasa t ? t
                : throwSinTasa(moneda, fecha);
    }

    /** Tasa a usar en un documento: 1 en pesos; la indicada por el usuario; o la vigente a la fecha. */
    public BigDecimal resolver(Long cia, String moneda, LocalDate fecha, BigDecimal indicada) {
        if (moneda == null || PESO.equals(moneda)) return BigDecimal.ONE;
        if (indicada != null) {
            if (indicada.signum() <= 0) throw new BusinessException("TASA_INVALIDA", "La tasa de cambio debe ser mayor que cero");
            return indicada.setScale(4, RoundingMode.HALF_UP);
        }
        return vigente(cia, moneda, fecha).tasa();
    }

    @Transactional
    public void eliminar(Long cia, String moneda, LocalDate fecha) {
        if (jdbc.update("delete from tasa_cambio where compania_id = ? and moneda = ? and fecha = ?", cia, moneda, Date.valueOf(fecha)) == 0) {
            throw new NotFoundException("Tasa de cambio", moneda + " " + fecha);
        }
    }

    private static Tasa throwSinTasa(String moneda, LocalDate fecha) {
        throw new BusinessException("TASA_NO_REGISTRADA", "No hay tasa de cambio de " + moneda + " al " + fecha + "; regístrela en Tasas de cambio");
    }
}
