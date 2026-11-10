package com.agroinsumos.backend.contabilidad;

import com.agroinsumos.backend.common.exception.BusinessException;
import com.agroinsumos.backend.contabilidad.fuente.DocumentoContable;
import com.agroinsumos.backend.contabilidad.fuente.FuenteContable;
import com.agroinsumos.backend.contabilidad.fuente.Partida;
import com.agroinsumos.backend.security.AuthUser;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.*;

/**
 * Asientos automáticos (legacy cntcontabilidad / transaccionesconta). Proceso por lotes e idempotente:
 * contabiliza los documentos del rango que aún no tienen asiento y reversa los anulados ya contabilizados.
 * Un documento con cuentas sin configurar no detiene el proceso: se informa y queda pendiente para la próxima corrida.
 * Se ejecuta bajo demanda (o al cierre del día) para que un error de configuración contable nunca impida facturar.
 */
@Service
@RequiredArgsConstructor
public class GeneracionAsientosService {

    public record Error(String origenTipo, Long origenId, LocalDate fecha, String documento, String mensaje) {}

    public record PorOrigen(String origenTipo, String descripcion, int asientos, int reversos, BigDecimal monto) {}

    public record Resultado(boolean simulacion, LocalDate desde, LocalDate hasta, int asientos, int reversos,
                            List<PorOrigen> porOrigen, List<Error> errores, List<AsientoService.Borrador> muestra) {}

    private final List<FuenteContable> fuentes;
    private final AsientoService asientos;
    private final ConfigContableService config;
    private final CuentaContableRepository cuentas;
    private final PeriodoContableService periodos;
    private final Clock clock;

    @Transactional
    public Resultado generar(AuthUser user, LocalDate desde, LocalDate hasta, boolean simular) {
        if (hasta.isBefore(desde)) throw new BusinessException("RANGO_INVALIDO", "La fecha final es anterior a la inicial");
        if (desde.plusYears(1).isBefore(hasta)) throw new BusinessException("RANGO_INVALIDO", "Genere como máximo un año a la vez");
        Long cia = user.companiaId();
        LocalDate inicio = config.inicio(cia).fechaInicio();
        if (inicio != null && inicio.isAfter(desde)) desde = inicio;
        var resolutor = new Resolutor(config.mapa(cia), cuentasPorCodigo(cia));
        LocalDate hoy = LocalDate.now(clock);
        List<Error> errores = new ArrayList<>();
        List<PorOrigen> resumen = new ArrayList<>();
        List<AsientoService.Borrador> muestra = new ArrayList<>();
        int totalAsientos = 0, totalReversos = 0;

        for (var fuente : fuentes) {
            int n = 0, rev = 0;
            BigDecimal monto = BigDecimal.ZERO;
            var documentos = desde.isAfter(hasta) ? List.<DocumentoContable>of() : fuente.pendientes(cia, desde, hasta);
            for (DocumentoContable doc : documentos) {
                try {
                    var b = new AsientoService.Borrador(doc.fecha(), Asiento.Tipo.AUTOMATICO, doc.origenTipo(), doc.origenId(), false,
                            doc.concepto(), resolutor.lineas(doc));
                    asientos.validar(cia, b);
                    if (!simular) asientos.registrar(user, b);
                    else if (muestra.size() < 50) muestra.add(b);
                    n++;
                    monto = monto.add(b.lineas().stream().map(l -> l.debito() == null ? BigDecimal.ZERO : l.debito())
                            .reduce(BigDecimal.ZERO, BigDecimal::add));
                } catch (BusinessException e) {
                    errores.add(new Error(doc.origenTipo(), doc.origenId(), doc.fecha(), doc.concepto(), e.getMessage()));
                }
            }
            for (Long id : fuente.anuladosPorReversar(cia)) {
                var original = asientos.deOrigen(cia, fuente.origen(), id).orElse(null);
                if (original == null) continue;
                // El reverso va en la fecha de la generación (dentro del rango), nunca antes del asiento original
                LocalDate fecha = hoy.isAfter(hasta) ? hasta : hoy;
                if (fecha.isBefore(original.getFecha())) fecha = original.getFecha();
                try {
                    var b = new AsientoService.Borrador(fecha, Asiento.Tipo.AUTOMATICO, original.getOrigenTipo(), id, true,
                            "Reverso por anulación: " + original.getConcepto(),
                            original.getLineas().stream().map(l -> new AsientoService.LineaRequest(l.getCuentaId(), l.getDescripcion(),
                                    l.getCredito(), l.getDebito(), l.getTerceroTipo(), l.getTerceroId())).toList());
                    asientos.validar(cia, b);
                    if (!simular) asientos.registrar(user, b);
                    rev++;
                } catch (BusinessException e) {
                    errores.add(new Error(fuente.origen(), id, fecha, original.getConcepto(), e.getMessage()));
                }
            }
            if (n + rev > 0) resumen.add(new PorOrigen(fuente.origen(), fuente.descripcion(), n, rev, monto));
            totalAsientos += n;
            totalReversos += rev;
        }
        return new Resultado(simular, desde, hasta, totalAsientos, totalReversos, resumen, errores, muestra);
    }

    private Map<String, CuentaContable> cuentasPorCodigo(Long cia) {
        Map<String, CuentaContable> m = new HashMap<>();
        cuentas.findByCompaniaIdOrderByCodigo(cia).forEach(c -> m.put(c.getCodigo(), c));
        return m;
    }

    /** Traduce códigos/claves a cuentas y agrupa las partidas por cuenta, lado y tercero. */
    private record Resolutor(Map<ClaveContable, String> config, Map<String, CuentaContable> cuentas) {

        List<AsientoService.LineaRequest> lineas(DocumentoContable doc) {
            record Clave(Long cuentaId, boolean debito, String terceroTipo, Long terceroId) {}
            Map<Clave, AsientoService.LineaRequest> agrupadas = new LinkedHashMap<>();
            for (Partida p : doc.partidas()) {
                CuentaContable c = resolver(p);
                boolean deb = p.debito().signum() > 0;
                var k = new Clave(c.getId(), deb, p.terceroTipo(), p.terceroId());
                agrupadas.merge(k, new AsientoService.LineaRequest(c.getId(), p.descripcion(), deb ? p.debito() : null,
                                deb ? null : p.credito(), p.terceroTipo(), p.terceroId()),
                        (a, b) -> new AsientoService.LineaRequest(a.cuentaId(), a.descripcion(), suma(a.debito(), b.debito()),
                                suma(a.credito(), b.credito()), a.terceroTipo(), a.terceroId()));
            }
            return new ArrayList<>(agrupadas.values());
        }

        private CuentaContable resolver(Partida p) {
            String codigo = p.codigo() == null || p.codigo().isBlank() ? null : p.codigo().trim();
            String fuente = p.origenCodigo();
            if (codigo == null) {
                if (p.clave() == null) {
                    throw new BusinessException("CUENTA_NO_INDICADA", "Indique la cuenta contable de " + fuente);
                }
                codigo = config.get(p.clave());
                fuente = "configuración " + p.clave().name();
                if (codigo == null) {
                    throw new BusinessException("CUENTA_NO_CONFIGURADA", "Configure la cuenta de " + p.clave().descripcion() + " (" + p.clave().name() + ")");
                }
            }
            CuentaContable c = cuentas.get(codigo);
            if (c == null) throw new BusinessException("CUENTA_INEXISTENTE", "La cuenta " + codigo + " (" + fuente + ") no existe en el catálogo");
            return c;
        }

        private static BigDecimal suma(BigDecimal a, BigDecimal b) {
            if (a == null) return b;
            if (b == null) return a;
            return a.add(b);
        }
    }
}
