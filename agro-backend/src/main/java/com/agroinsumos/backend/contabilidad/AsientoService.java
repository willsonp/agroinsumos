package com.agroinsumos.backend.contabilidad;

import com.agroinsumos.backend.common.crud.NumeradorService;
import com.agroinsumos.backend.common.exception.BusinessException;
import com.agroinsumos.backend.common.exception.NotFoundException;
import com.agroinsumos.backend.security.AuthUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.util.*;

/** Diario general: registro de asientos balanceados (manuales y automáticos) y su anulación. */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AsientoService {

    public record LineaRequest(@NotNull Long cuentaId, @Size(max = 200) String descripcion,
                               @DecimalMin("0") BigDecimal debito, @DecimalMin("0") BigDecimal credito,
                               @Pattern(regexp = "[CP]") String terceroTipo, Long terceroId) {}

    public record Request(@NotNull LocalDate fecha, @NotBlank @Size(max = 200) String concepto,
                          @NotEmpty @Size(min = 2, max = 200) List<@Valid LineaRequest> lineas) {}

    public record AnularRequest(@NotBlank @Size(max = 200) String motivo) {}

    public record LineaResponse(int linea, Long cuentaId, String cuentaCodigo, String cuentaNombre, String descripcion,
                                BigDecimal debito, BigDecimal credito, String terceroTipo, Long terceroId) {}

    public record Response(Long id, long numero, LocalDate fecha, Asiento.Tipo tipo, String origenTipo, Long origenId,
                           boolean reverso, String concepto, BigDecimal total, Asiento.Estado estado, String motivoAnulacion,
                           List<LineaResponse> lineas) {}

    /** Asiento por registrar (lo usan el registro manual y la generación automática). */
    public record Borrador(LocalDate fecha, Asiento.Tipo tipo, String origenTipo, Long origenId, boolean reverso,
                           String concepto, List<LineaRequest> lineas) {}

    private final AsientoRepository repo;
    private final CuentaContableRepository cuentas;
    private final PeriodoContableService periodos;
    private final NumeradorService numerador;
    private final Clock clock;

    @Transactional
    public Response crearManual(AuthUser user, Request r) {
        if (r.fecha().isAfter(LocalDate.now(clock))) throw new BusinessException("FECHA_FUTURA", "La fecha no puede ser futura");
        return respuesta(registrar(user, new Borrador(r.fecha(), Asiento.Tipo.MANUAL, null, null, false, r.concepto().trim(), r.lineas())));
    }

    /** Valida partida doble, cuentas de detalle activas y período abierto; numera y guarda. */
    @Transactional
    public Asiento registrar(AuthUser user, Borrador b) {
        Long cia = user.companiaId();
        validar(cia, b);
        var a = new Asiento();
        a.setCompaniaId(cia);
        a.setNumero(numerador.siguiente(cia, "ASIENTO"));
        a.setFecha(b.fecha());
        a.setTipo(b.tipo());
        a.setOrigenTipo(b.origenTipo());
        a.setOrigenId(b.origenId());
        a.setReverso(b.reverso());
        a.setConcepto(b.concepto().length() > 200 ? b.concepto().substring(0, 200) : b.concepto());
        a.setUsuarioId(user.usuarioId());
        int n = 1;
        BigDecimal total = BigDecimal.ZERO;
        for (var l : b.lineas()) {
            var linea = new Asiento.Linea();
            linea.setAsiento(a);
            linea.setLinea(n++);
            linea.setCuentaId(l.cuentaId());
            linea.setDescripcion(l.descripcion() == null || l.descripcion().isBlank() ? null
                    : l.descripcion().length() > 200 ? l.descripcion().substring(0, 200) : l.descripcion());
            linea.setDebito(cero(l.debito()));
            linea.setCredito(cero(l.credito()));
            linea.setTerceroTipo(l.terceroTipo());
            linea.setTerceroId(l.terceroId());
            a.getLineas().add(linea);
            total = total.add(linea.getDebito());
        }
        a.setTotal(total);
        return repo.save(a);
    }

    /** Lanza BusinessException con el primer problema encontrado. */
    public void validar(Long cia, Borrador b) {
        if (b.lineas().size() < 2) throw new BusinessException("ASIENTO_INCOMPLETO", "Un asiento necesita al menos dos líneas");
        BigDecimal debitos = BigDecimal.ZERO, creditos = BigDecimal.ZERO;
        Map<Long, CuentaContable> vistas = new HashMap<>();
        for (var l : b.lineas()) {
            BigDecimal d = cero(l.debito()), c = cero(l.credito());
            if (d.scale() > 2 || c.scale() > 2) throw new BusinessException("MONTO_INVALIDO", "Use montos con dos decimales");
            if ((d.signum() > 0) == (c.signum() > 0)) {
                throw new BusinessException("LINEA_INVALIDA", "Cada línea lleva un débito o un crédito (no ambos ni ninguno)");
            }
            var cuenta = vistas.computeIfAbsent(l.cuentaId(), id -> cuentas.findById(id)
                    .filter(x -> x.getCompaniaId().equals(cia)).orElseThrow(() -> new NotFoundException("Cuenta contable", id)));
            if (!cuenta.isAceptaMovimiento()) {
                throw new BusinessException("CUENTA_DE_GRUPO", "La cuenta " + cuenta.getCodigo() + " es de grupo y no acepta movimientos");
            }
            if (!"A".equals(cuenta.getEstado())) throw new BusinessException("CUENTA_INACTIVA", "La cuenta " + cuenta.getCodigo() + " está inactiva");
            debitos = debitos.add(d);
            creditos = creditos.add(c);
        }
        if (debitos.compareTo(creditos) != 0) {
            throw new BusinessException("ASIENTO_DESBALANCEADO", "Débitos RD$" + debitos + " ≠ créditos RD$" + creditos);
        }
        periodos.exigirAbierto(cia, b.fecha());
        if (!CierreAnualService.ORIGEN.equals(b.origenTipo())) exigirEjercicioAbierto(cia, b.fecha());
    }

    /** Período mensual abierto y ejercicio sin asiento de cierre anual. */
    public void exigirPeriodoAbierto(Long cia, LocalDate fecha) { periodos.exigirAbierto(cia, fecha); }

    private void exigirEjercicioAbierto(Long cia, LocalDate fecha) {
        if (repo.existsByCompaniaIdAndOrigenTipoAndOrigenIdAndEstado(cia, CierreAnualService.ORIGEN, (long) fecha.getYear(), Asiento.Estado.REGISTRADO)) {
            throw new BusinessException("EJERCICIO_CERRADO", "El ejercicio " + fecha.getYear() + " está cerrado; anule el cierre anual para registrar en él");
        }
    }

    /** Solo asientos manuales; los automáticos se reversan anulando su documento. */
    @Transactional
    public Response anular(AuthUser user, Long id, String motivo) {
        var a = cargar(user.companiaId(), id);
        if (a.getTipo() != Asiento.Tipo.MANUAL) {
            throw new BusinessException("ANULAR_DESDE_ORIGEN", "El asiento proviene de " + a.getOrigenTipo() + " " + a.getOrigenId() + "; anule ese documento");
        }
        if (a.getEstado() == Asiento.Estado.ANULADO) throw new BusinessException("ASIENTO_ANULADO", "El asiento ya está anulado");
        periodos.exigirAbierto(user.companiaId(), a.getFecha());
        exigirEjercicioAbierto(user.companiaId(), a.getFecha());
        a.setEstado(Asiento.Estado.ANULADO);
        a.setMotivoAnulacion(motivo);
        return respuesta(a);
    }

    public Response obtener(Long cia, Long id) { return respuesta(cargar(cia, id)); }

    public Page<Response> buscar(Long cia, LocalDate desde, LocalDate hasta, Asiento.Tipo tipo, String origenTipo, Long cuentaId, Pageable p) {
        return repo.buscar(cia, desde, hasta, tipo, origenTipo, cuentaId, p).map(this::respuesta);
    }

    public Optional<Asiento> deOrigen(Long cia, String origenTipo, Long origenId) {
        return repo.findByCompaniaIdAndOrigenTipoAndOrigenIdAndReversoFalseAndEstado(cia, origenTipo, origenId, Asiento.Estado.REGISTRADO);
    }

    Asiento cargar(Long cia, Long id) {
        return repo.findByIdAndCompaniaId(id, cia).orElseThrow(() -> new NotFoundException("Asiento", id));
    }

    Response respuesta(Asiento a) {
        Map<Long, CuentaContable> cache = new HashMap<>();
        return new Response(a.getId(), a.getNumero(), a.getFecha(), a.getTipo(), a.getOrigenTipo(), a.getOrigenId(), a.isReverso(),
                a.getConcepto(), a.getTotal(), a.getEstado(), a.getMotivoAnulacion(),
                a.getLineas().stream().map(l -> {
                    var c = cache.computeIfAbsent(l.getCuentaId(), id -> cuentas.findById(id).orElseThrow());
                    return new LineaResponse(l.getLinea(), l.getCuentaId(), c.getCodigo(), c.getNombre(), l.getDescripcion(),
                            l.getDebito(), l.getCredito(), l.getTerceroTipo(), l.getTerceroId());
                }).toList());
    }

    private static BigDecimal cero(BigDecimal b) {
        if (b == null) return BigDecimal.ZERO.setScale(2);
        BigDecimal s = b.stripTrailingZeros();
        return s.scale() > 2 ? s : b.setScale(2, RoundingMode.UNNECESSARY);
    }
}
