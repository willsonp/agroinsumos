package com.agroinsumos.backend.banco;

import com.agroinsumos.backend.common.crud.CrudService;
import com.agroinsumos.backend.common.exception.BusinessException;
import jakarta.validation.constraints.*;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Objects;

/** Cuentas bancarias (legacy detallebanco / FrmCuentaBancaria). */
@Service
@RequiredArgsConstructor
public class CuentaBancariaService extends CrudService<CuentaBancaria, CuentaBancariaService.Request, CuentaBancariaService.Response> {

    public record Request(@NotNull Long bancoId, @NotBlank @Size(max = 30) String numero, @NotBlank @Size(max = 60) String nombre,
                          @Pattern(regexp = "CORRIENTE|AHORRO") String tipo, @NotNull BigDecimal saldoInicial,
                          LocalDate fechaSaldoInicial, @Size(max = 12) String cuentaContable, @Min(1) Long proximoCheque,
                          /** DOP por defecto; las cuentas en otra moneda deben tener su propia cuenta contable. */
                          @Pattern(regexp = "[A-Z]{3}") String moneda) {}

    public record Response(Long id, Long bancoId, String banco, String numero, String nombre, String tipo, String moneda,
                           BigDecimal saldoInicial, LocalDate fechaSaldoInicial, String cuentaContable, long proximoCheque,
                           BigDecimal saldo, String estado) {}

    private final CuentaBancariaRepository repo;
    private final BancoService bancos;
    private final MovimientoBancarioRepository movimientos;
    private final Clock clock;

    @Override protected JpaRepository<CuentaBancaria, Long> repo() { return repo; }
    @Override protected String recurso() { return "Cuenta bancaria"; }
    @Override protected Page<CuentaBancaria> buscar(Long c, String q, Pageable p) { return repo.buscar(c, q, p); }

    @Override
    protected CuentaBancaria nueva(Long companiaId) {
        var c = new CuentaBancaria();
        c.setCompaniaId(companiaId);
        return c;
    }

    @Override
    protected void validar(CuentaBancaria c, Request r, Long companiaId) {
        bancos.cargarActiva(companiaId, r.bancoId());
        boolean cambia = !Objects.equals(c.getBancoId(), r.bancoId()) || !Objects.equals(c.getNumero(), r.numero().trim());
        if (cambia && repo.existsByCompaniaIdAndBancoIdAndNumero(companiaId, r.bancoId(), r.numero().trim())) {
            throw new BusinessException(HttpStatus.CONFLICT, "CUENTA_DUPLICADA", "La cuenta " + r.numero() + " ya existe en ese banco");
        }
        if (c.getId() != null && c.getSaldoInicial().compareTo(r.saldoInicial()) != 0 && movimientos.countByCuentaId(c.getId()) > 0) {
            throw new BusinessException("CUENTA_CON_MOVIMIENTOS", "No se puede cambiar el saldo inicial de una cuenta con movimientos");
        }
        String moneda = r.moneda() == null ? "DOP" : r.moneda();
        if (c.getId() != null && !moneda.equals(c.getMoneda()) && movimientos.countByCuentaId(c.getId()) > 0) {
            throw new BusinessException("CUENTA_CON_MOVIMIENTOS", "No se puede cambiar la moneda de una cuenta con movimientos");
        }
    }

    @Override
    protected void aplicar(CuentaBancaria c, Request r, Long companiaId) {
        c.setBancoId(r.bancoId());
        c.setNumero(r.numero().trim());
        c.setNombre(r.nombre().trim());
        c.setTipo(r.tipo() == null ? "CORRIENTE" : r.tipo());
        c.setSaldoInicial(r.saldoInicial());
        c.setFechaSaldoInicial(r.fechaSaldoInicial() != null ? r.fechaSaldoInicial()
                : c.getFechaSaldoInicial() != null ? c.getFechaSaldoInicial() : LocalDate.now(clock));
        c.setCuentaContable(r.cuentaContable());
        if (r.proximoCheque() != null) c.setProximoCheque(r.proximoCheque());
        c.setMoneda(r.moneda() == null ? "DOP" : r.moneda());
    }

    @Override
    protected Response respuesta(CuentaBancaria c) {
        String banco = bancos.cargar(c.getCompaniaId(), c.getBancoId()).getNombre();
        return new Response(c.getId(), c.getBancoId(), banco, c.getNumero(), c.getNombre(), c.getTipo(), c.getMoneda(),
                c.getSaldoInicial(), c.getFechaSaldoInicial(), c.getCuentaContable(), c.getProximoCheque(),
                c.getId() == null ? c.getSaldoInicial() : saldo(c, null), c.getEstado());
    }

    /** Saldo en libros: saldo inicial + entradas - salidas (movimientos no anulados, hasta la fecha si se indica). */
    public BigDecimal saldo(CuentaBancaria c, LocalDate hasta) {
        return c.getSaldoInicial().add(movimientos.netoHasta(c.getId(), hasta));
    }
}
