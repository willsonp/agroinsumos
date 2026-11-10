package com.agroinsumos.backend.cxc.financiamiento;

import com.agroinsumos.backend.catalogo.cliente.ClienteService;
import com.agroinsumos.backend.common.crud.NumeradorService;
import com.agroinsumos.backend.common.exception.BusinessException;
import com.agroinsumos.backend.common.exception.NotFoundException;
import com.agroinsumos.backend.cxc.NotaDebito;
import com.agroinsumos.backend.cxc.NotaDebitoRepository;
import com.agroinsumos.backend.cxc.NotaDebitoService;
import com.agroinsumos.backend.security.AuthUser;
import com.agroinsumos.backend.venta.factura.Factura;
import com.agroinsumos.backend.venta.factura.FacturaService;
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
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Ventas financiadas. El plan reparte en cuotas el saldo de una factura a crédito más el interés; el interés se
 * factura con una nota de débito e-CF 33 exenta. Los cobros son recibos normales a la factura y a la nota: lo cobrado
 * se aplica a las cuotas en orden de vencimiento. La mora (% de la cuota vencida tras los días de gracia) se cobra con
 * otra nota de débito, una vez por cuota. Legacy: cuotas, Agrega_Cuotas, Actualiza_BalanceCuotas, ConsultaFecha_Vencimiento*.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class FinanciamientoService {

    public record Request(@NotNull Long facturaId, @NotNull @DecimalMin("0") @DecimalMax("200") BigDecimal tasaInteres,
                          @NotNull @Min(1) @Max(120) Integer cantidadCuotas, @NotNull Integer frecuenciaDias,
                          LocalDate primeraCuota, @DecimalMin("0") @DecimalMax("100") BigDecimal moraPorcentaje,
                          @Min(0) @Max(90) Integer diasGracia) {}

    public record CancelarRequest(@NotBlank @Size(max = 200) String motivo) {}

    public record CuotaEstado(int numero, LocalDate vencimiento, BigDecimal monto, BigDecimal pagado, BigDecimal pendiente,
                              int diasAtraso, boolean vencida, BigDecimal moraPorCobrar, boolean moraCobrada) {}

    public record Response(Long id, long numero, LocalDate fecha, Long facturaId, long facturaNumero, String encf, Long clienteId,
                           String cliente, BigDecimal pagadoPrevio, BigDecimal capital, BigDecimal tasaInteres, BigDecimal interes,
                           Long notaDebitoId, int cantidadCuotas, int frecuenciaDias, BigDecimal moraPorcentaje, int diasGracia,
                           PlanFinanciamiento.Estado estado, BigDecimal totalPlan, BigDecimal cobrado, BigDecimal pendiente,
                           int cuotasVencidas, BigDecimal montoVencido, BigDecimal moraPorCobrar, List<CuotaEstado> cuotas) {}

    public record CuotaVencida(Long planId, long planNumero, Long clienteId, String cliente, String telefono, Long zonaId,
                               long facturaNumero, int cuota, LocalDate vencimiento, int diasAtraso, BigDecimal pendiente,
                               BigDecimal moraPorCobrar) {}

    private final PlanFinanciamientoRepository repo;
    private final FacturaService facturas;
    private final NotaDebitoService notasDebito;
    private final NotaDebitoRepository notasDebitoRepo;
    private final ClienteService clientes;
    private final NumeradorService numerador;
    private final Clock clock;

    @Transactional
    public Response crear(AuthUser user, Request r) {
        Long cia = user.companiaId();
        if (r.frecuenciaDias() != 7 && r.frecuenciaDias() != 15 && r.frecuenciaDias() != 30) {
            throw new BusinessException("FRECUENCIA_INVALIDA", "Frecuencia de pago: 7 (semanal), 15 (quincenal) o 30 (mensual)");
        }
        Factura f = facturas.cargar(cia, r.facturaId());
        if (f.getCondicion() != Factura.Condicion.CREDITO) throw new BusinessException("FACTURA_NO_CREDITO", "Solo se financian facturas a crédito");
        if (f.getEstado() != Factura.Estado.EMITIDA || f.getBalance().signum() <= 0) {
            throw new BusinessException("FACTURA_SIN_BALANCE", "La factura no tiene balance pendiente");
        }
        if (repo.existsByFacturaIdAndEstado(f.getId(), PlanFinanciamiento.Estado.ACTIVO)) {
            throw new BusinessException("FACTURA_YA_FINANCIADA", "La factura " + f.getNumero() + " ya tiene un plan activo");
        }
        LocalDate hoy = LocalDate.now(clock);
        var p = new PlanFinanciamiento();
        p.setCompaniaId(cia);
        p.setNumero(numerador.siguiente(cia, "FINANCIAMIENTO"));
        p.setFecha(hoy);
        p.setFacturaId(f.getId());
        p.setClienteId(f.getClienteId());
        p.setPagadoPrevio(f.getTotal().subtract(f.getBalance()));
        p.setCapital(f.getBalance());
        p.setTasaInteres(r.tasaInteres());
        p.setInteres(f.getBalance().multiply(r.tasaInteres()).divide(new BigDecimal("100"), 2, RoundingMode.HALF_UP));
        p.setCantidadCuotas(r.cantidadCuotas());
        p.setFrecuenciaDias(r.frecuenciaDias());
        p.setMoraPorcentaje(r.moraPorcentaje() == null ? BigDecimal.ZERO : r.moraPorcentaje());
        p.setDiasGracia(r.diasGracia() == null ? 0 : r.diasGracia());
        p.setUsuarioId(user.usuarioId());

        // Cuotas iguales; la última absorbe el redondeo
        BigDecimal total = p.getCapital().add(p.getInteres());
        BigDecimal cuota = total.divide(BigDecimal.valueOf(r.cantidadCuotas()), 2, RoundingMode.HALF_UP);
        LocalDate venc = r.primeraCuota() != null ? r.primeraCuota() : siguiente(hoy, r.frecuenciaDias(), 1);
        if (venc.isBefore(hoy)) throw new BusinessException("FECHA_INVALIDA", "La primera cuota no puede vencer en el pasado");
        BigDecimal asignado = BigDecimal.ZERO;
        for (int i = 1; i <= r.cantidadCuotas(); i++) {
            var c = new PlanFinanciamiento.Cuota();
            c.setPlan(p);
            c.setNumero(i);
            c.setVencimiento(siguiente(venc, r.frecuenciaDias(), i - 1));
            c.setMonto(i == r.cantidadCuotas() ? total.subtract(asignado) : cuota);
            asignado = asignado.add(c.getMonto());
            p.getCuotas().add(c);
        }
        if (p.getCuotas().stream().anyMatch(c -> c.getMonto().signum() <= 0)) {
            throw new BusinessException("CUOTAS_INVALIDAS", "Demasiadas cuotas para el monto financiado");
        }
        LocalDate ultima = p.getCuotas().getLast().getVencimiento();
        if (p.getInteres().signum() > 0) {
            var nd = notasDebito.emitir(user, new NotaDebitoService.Request(f.getId(),
                    "Intereses de financiamiento " + r.tasaInteres().stripTrailingZeros().toPlainString() + "% en " + r.cantidadCuotas() + " cuotas",
                    p.getInteres(), 4));
            p.setNotaDebitoId(nd.id());
            notasDebitoRepo.findById(nd.id()).ifPresent(n -> n.setFechaVencimiento(ultima));
        }
        // La factura vence con la última cuota (la antigüedad de saldos usa el plan, no el plazo original)
        f.setFechaVencimiento(ultima);
        repo.save(p);
        return respuesta(cia, p, hoy);
    }

    /** Emite la nota de débito por la mora de las cuotas vencidas (cada cuota se penaliza una sola vez). */
    @Transactional
    public Response cobrarMora(AuthUser user, Long id) {
        Long cia = user.companiaId();
        var p = cargar(cia, id);
        if (p.getEstado() != PlanFinanciamiento.Estado.ACTIVO) throw new BusinessException("PLAN_CANCELADO", "El plan está cancelado");
        LocalDate hoy = LocalDate.now(clock);
        var estado = calcular(cia, p, hoy);
        BigDecimal mora = BigDecimal.ZERO;
        List<Integer> numeros = new ArrayList<>();
        for (var c : estado) if (c.moraPorCobrar().signum() > 0) { mora = mora.add(c.moraPorCobrar()); numeros.add(c.numero()); }
        if (mora.signum() <= 0) throw new BusinessException("SIN_MORA", "No hay cuotas con mora por cobrar");
        var nd = notasDebito.emitir(user, new NotaDebitoService.Request(p.getFacturaId(),
                "Mora cuota(s) " + numeros.stream().map(String::valueOf).reduce((a, b) -> a + ", " + b).orElse("") + " del financiamiento " + p.getNumero(),
                mora, 4));
        p.getCuotas().stream().filter(c -> numeros.contains(c.getNumero())).forEach(c -> c.setMoraNotaDebitoId(nd.id()));
        return respuesta(cia, p, hoy);
    }

    @Transactional
    public Response cancelar(AuthUser user, Long id, String motivo) {
        var p = cargar(user.companiaId(), id);
        if (p.getEstado() != PlanFinanciamiento.Estado.ACTIVO) throw new BusinessException("PLAN_CANCELADO", "El plan ya está cancelado");
        p.setEstado(PlanFinanciamiento.Estado.CANCELADO);
        return respuesta(user.companiaId(), p, LocalDate.now(clock));
    }

    public Response obtener(Long cia, Long id) { return respuesta(cia, cargar(cia, id), LocalDate.now(clock)); }

    public Page<Response> buscar(Long cia, Long clienteId, PlanFinanciamiento.Estado estado, Pageable pageable) {
        LocalDate hoy = LocalDate.now(clock);
        return repo.buscar(cia, clienteId, estado, pageable).map(p -> respuesta(cia, p, hoy));
    }

    /** Cuotas vencidas de los planes activos (legacy RptCuotasVencidas, por cliente y por zona). */
    public List<CuotaVencida> vencidas(Long cia, LocalDate fecha, Long clienteId, Long zonaId) {
        LocalDate corte = fecha != null ? fecha : LocalDate.now(clock);
        List<CuotaVencida> res = new ArrayList<>();
        for (var p : repo.findByCompaniaIdAndEstado(cia, PlanFinanciamiento.Estado.ACTIVO)) {
            if (clienteId != null && !clienteId.equals(p.getClienteId())) continue;
            var cliente = clientes.cargar(cia, p.getClienteId());
            if (zonaId != null && !zonaId.equals(cliente.getZonaId())) continue;
            var f = facturas.cargar(cia, p.getFacturaId());
            for (var c : calcular(cia, p, corte)) {
                if (!c.vencida()) continue;
                res.add(new CuotaVencida(p.getId(), p.getNumero(), cliente.getId(), cliente.getNombre(),
                        cliente.getCelular() != null ? cliente.getCelular() : cliente.getTelefono(), cliente.getZonaId(), f.getNumero(),
                        c.numero(), c.vencimiento(), c.diasAtraso(), c.pendiente(), c.moraPorCobrar()));
            }
        }
        res.sort(Comparator.comparing(CuotaVencida::cliente).thenComparing(CuotaVencida::vencimiento));
        return res;
    }

    // ------------------------------------------------------------------ cálculo
    /** Lo cobrado (factura después del plan + nota de intereses) se aplica a las cuotas en orden. */
    private List<CuotaEstado> calcular(Long cia, PlanFinanciamiento p, LocalDate hoy) {
        var f = facturas.cargar(cia, p.getFacturaId());
        BigDecimal cobrado = f.getTotal().subtract(f.getBalance()).subtract(p.getPagadoPrevio());
        if (p.getNotaDebitoId() != null) {
            NotaDebito nd = notasDebitoRepo.findById(p.getNotaDebitoId()).orElseThrow();
            cobrado = cobrado.add(nd.getTotal().subtract(nd.getBalance()));
        }
        BigDecimal restante = cobrado.max(BigDecimal.ZERO);
        List<CuotaEstado> res = new ArrayList<>();
        for (var c : p.getCuotas()) {
            BigDecimal pagado = restante.min(c.getMonto());
            restante = restante.subtract(pagado);
            BigDecimal pendiente = c.getMonto().subtract(pagado);
            boolean vencida = pendiente.signum() > 0 && c.getVencimiento().isBefore(hoy);
            int dias = vencida ? (int) ChronoUnit.DAYS.between(c.getVencimiento(), hoy) : 0;
            boolean aplicaMora = vencida && dias > p.getDiasGracia() && p.getMoraPorcentaje().signum() > 0
                    && c.getMoraNotaDebitoId() == null && p.getEstado() == PlanFinanciamiento.Estado.ACTIVO;
            BigDecimal mora = aplicaMora ? pendiente.multiply(p.getMoraPorcentaje()).divide(new BigDecimal("100"), 2, RoundingMode.HALF_UP)
                    : BigDecimal.ZERO;
            res.add(new CuotaEstado(c.getNumero(), c.getVencimiento(), c.getMonto(), pagado, pendiente, dias, vencida, mora,
                    c.getMoraNotaDebitoId() != null));
        }
        return res;
    }

    private static LocalDate siguiente(LocalDate base, int frecuencia, int n) {
        return frecuencia == 30 ? base.plusMonths(n) : base.plusDays((long) frecuencia * n);
    }

    private PlanFinanciamiento cargar(Long cia, Long id) {
        return repo.findByIdAndCompaniaId(id, cia).orElseThrow(() -> new NotFoundException("Plan de financiamiento", id));
    }

    private Response respuesta(Long cia, PlanFinanciamiento p, LocalDate hoy) {
        var f = facturas.cargar(cia, p.getFacturaId());
        var cuotas = calcular(cia, p, hoy);
        BigDecimal total = p.getCapital().add(p.getInteres());
        BigDecimal cobrado = cuotas.stream().map(CuotaEstado::pagado).reduce(BigDecimal.ZERO, BigDecimal::add);
        var vencidas = cuotas.stream().filter(CuotaEstado::vencida).toList();
        return new Response(p.getId(), p.getNumero(), p.getFecha(), f.getId(), f.getNumero(), f.comprobante(), p.getClienteId(),
                f.getClienteNombre(), p.getPagadoPrevio(), p.getCapital(), p.getTasaInteres(), p.getInteres(), p.getNotaDebitoId(),
                p.getCantidadCuotas(), p.getFrecuenciaDias(), p.getMoraPorcentaje(), p.getDiasGracia(), p.getEstado(), total, cobrado,
                total.subtract(cobrado), vencidas.size(), vencidas.stream().map(CuotaEstado::pendiente).reduce(BigDecimal.ZERO, BigDecimal::add),
                cuotas.stream().map(CuotaEstado::moraPorCobrar).reduce(BigDecimal.ZERO, BigDecimal::add), cuotas);
    }
}
