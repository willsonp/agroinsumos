package com.agroinsumos.backend.banco;

import com.agroinsumos.backend.common.exception.BusinessException;
import com.agroinsumos.backend.common.exception.NotFoundException;
import com.agroinsumos.backend.security.AuthUser;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Conciliación bancaria (legacy FrmConciliar: Conciliacion_Transacciones, Agrega_ConciliacionBancaria).
 * Se marcan los movimientos que aparecen en el estado de cuenta del banco; los no marcados quedan
 * "en tránsito". Solo se cierra si cuadra:
 *   saldo banco = saldo en libros - depósitos en tránsito + cheques/débitos en tránsito.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ConciliacionService {

    public record Request(@NotNull Long cuentaId, @NotNull LocalDate fechaCorte, @NotNull BigDecimal saldoBanco,
                          @NotNull Set<Long> movimientoIds, @Size(max = 200) String observacion) {}

    public record Resultado(Long cuentaId, LocalDate fechaCorte, LocalDate ultimaConciliacion, BigDecimal saldoLibros,
                            BigDecimal depositosTransito, BigDecimal chequesTransito, BigDecimal saldoConciliado,
                            BigDecimal saldoBanco, BigDecimal diferencia, boolean cuadra,
                            List<MovimientoBancarioService.Response> pendientes) {}

    public record ConciliacionResponse(Long id, Long cuentaId, LocalDate fechaCorte, BigDecimal saldoBanco, BigDecimal saldoLibros,
                                       BigDecimal depositosTransito, BigDecimal chequesTransito, String observacion) {
        static ConciliacionResponse de(ConciliacionBancaria c) {
            return new ConciliacionResponse(c.getId(), c.getCuentaId(), c.getFechaCorte(), c.getSaldoBanco(), c.getSaldoLibros(),
                    c.getDepositosTransito(), c.getChequesTransito(), c.getObservacion());
        }
    }

    private final ConciliacionBancariaRepository repo;
    private final MovimientoBancarioRepository movimientos;
    private final CuentaBancariaService cuentas;

    /** Vista previa: calcula el cuadre con los movimientos marcados (no guarda nada). */
    public Resultado calcular(Long cia, Request r) {
        CuentaBancaria cuenta = cuentas.cargar(cia, r.cuentaId());
        var ultima = repo.ultimaFechaCorte(cuenta.getId());
        if (ultima.isPresent() && !r.fechaCorte().isAfter(ultima.get())) {
            throw new BusinessException("FECHA_CORTE_INVALIDA", "Ya existe una conciliación al " + ultima.get());
        }
        List<MovimientoBancario> pendientes = movimientos.pendientesDeConciliar(cuenta.getId(), r.fechaCorte());
        Set<Long> ids = new HashSet<>();
        pendientes.forEach(m -> ids.add(m.getId()));
        if (!ids.containsAll(r.movimientoIds())) {
            throw new BusinessException("MOVIMIENTO_INVALIDO", "Hay movimientos marcados que no están pendientes en esta cuenta y fecha");
        }
        BigDecimal saldoLibros = cuentas.saldo(cuenta, r.fechaCorte());
        BigDecimal depTransito = BigDecimal.ZERO, chqTransito = BigDecimal.ZERO;
        for (var m : pendientes) {
            if (r.movimientoIds().contains(m.getId())) continue;
            if (m.getTipo().entrada()) depTransito = depTransito.add(m.getMonto());
            else chqTransito = chqTransito.add(m.getMonto());
        }
        BigDecimal conciliado = saldoLibros.subtract(depTransito).add(chqTransito);
        BigDecimal diferencia = r.saldoBanco().subtract(conciliado);
        return new Resultado(cuenta.getId(), r.fechaCorte(), ultima.orElse(null), saldoLibros, depTransito, chqTransito,
                conciliado, r.saldoBanco(), diferencia, diferencia.signum() == 0,
                pendientes.stream().map(MovimientoBancarioService.Response::de).toList());
    }

    @Transactional
    public ConciliacionResponse conciliar(AuthUser user, Request r) {
        Resultado res = calcular(user.companiaId(), r);
        if (!res.cuadra()) {
            throw new BusinessException("CONCILIACION_NO_CUADRA", "La conciliación no cuadra: diferencia RD$" + res.diferencia());
        }
        var c = new ConciliacionBancaria();
        c.setCompaniaId(user.companiaId());
        c.setCuentaId(r.cuentaId());
        c.setFechaCorte(r.fechaCorte());
        c.setSaldoBanco(r.saldoBanco());
        c.setSaldoLibros(res.saldoLibros());
        c.setDepositosTransito(res.depositosTransito());
        c.setChequesTransito(res.chequesTransito());
        c.setObservacion(r.observacion());
        c.setUsuarioId(user.usuarioId());
        repo.save(c);
        movimientos.findAllById(r.movimientoIds()).forEach(m -> m.setConciliacionId(c.getId()));
        return ConciliacionResponse.de(c);
    }

    public List<ConciliacionResponse> historial(Long cia, Long cuentaId) {
        cuentas.cargar(cia, cuentaId);
        return repo.findByCuentaIdOrderByFechaCorteDesc(cuentaId).stream().map(ConciliacionResponse::de).toList();
    }

    public ConciliacionResponse obtener(Long cia, Long id) {
        return repo.findById(id).filter(c -> c.getCompaniaId().equals(cia)).map(ConciliacionResponse::de)
                .orElseThrow(() -> new NotFoundException("Conciliación", id));
    }
}
