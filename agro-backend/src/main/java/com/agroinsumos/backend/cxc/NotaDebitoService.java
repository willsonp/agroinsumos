package com.agroinsumos.backend.cxc;

import com.agroinsumos.backend.common.crud.NumeradorService;
import com.agroinsumos.backend.common.exception.BusinessException;
import com.agroinsumos.backend.common.exception.NotFoundException;
import com.agroinsumos.backend.ecf.TipoEcf;
import com.agroinsumos.backend.ecf.dto.EcfDtos;
import com.agroinsumos.backend.ecf.emision.ComprobanteFiscalService;
import com.agroinsumos.backend.security.AuthUser;
import com.agroinsumos.backend.venta.factura.Factura;
import com.agroinsumos.backend.venta.factura.FacturaService;
import jakarta.validation.constraints.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;

/** Notas de débito a clientes (legacy notadebito / FrmNotaDebitoCXC). Emiten e-CF 33. */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class NotaDebitoService {

    public record Request(@NotNull Long facturaId, @NotBlank @Size(max = 90) String concepto,
                          @NotNull @DecimalMin("0.01") BigDecimal monto,
                          /** 1 = gravado ITBIS 18% (se suma), 4 = exento. Por defecto 4 (p.ej. mora/intereses). */
                          @Min(1) @Max(4) Integer indicadorItbis) {}

    public record Response(Long id, long numero, LocalDate fecha, LocalDate fechaVencimiento, Long clienteId,
                           Long facturaId, String concepto, BigDecimal totalItbis, BigDecimal total,
                           BigDecimal balance, String encf, Long ecfDocumentoId, NotaDebito.Estado estado) {
        static Response de(NotaDebito n) {
            return new Response(n.getId(), n.getNumero(), n.getFecha(), n.getFechaVencimiento(), n.getClienteId(),
                    n.getFacturaId(), n.getConcepto(), n.getTotalItbis(), n.getTotal(), n.getBalance(), n.getEncf(),
                    n.getEcfDocumentoId(), n.getEstado());
        }
    }

    private final NotaDebitoRepository repo;
    private final FacturaService facturas;
    private final ComprobanteFiscalService ecf;   // e-CF, NCF serie B o sin comprobante, según la empresa
    private final NumeradorService numerador;
    private final Clock clock;

    @Transactional
    public Response emitir(AuthUser user, Request r) {
        Long cia = user.companiaId();
        Factura f = facturas.cargar(cia, r.facturaId());
        if (f.getEstado() == Factura.Estado.ANULADA) throw new BusinessException("FACTURA_ANULADA", "La factura está anulada");
        if (f.comprobante() == null) throw new BusinessException("FACTURA_SIN_COMPROBANTE", "La factura no tiene NCF/e-NCF");
        int indicador = r.indicadorItbis() == null ? 4 : r.indicadorItbis();
        LocalDate hoy = LocalDate.now(clock);

        var n = new NotaDebito();
        n.setCompaniaId(cia);
        n.setNumero(numerador.siguiente(cia, "NOTA_DEBITO"));
        n.setFecha(hoy);
        n.setFechaVencimiento(hoy.plusDays(Math.max(f.getDiasCredito(), 0)));
        n.setClienteId(f.getClienteId());
        n.setFacturaId(f.getId());
        n.setConcepto(r.concepto().trim());
        n.setUsuarioId(user.usuarioId());

        var item = new EcfDtos.Item(n.getConcepto(), 2, BigDecimal.ONE, r.monto(), null, indicador);
        var comprador = f.getClienteRnc() == null ? null : new EcfDtos.Comprador(f.getClienteRnc(), f.getClienteNombre(), null, null);
        // Se persiste primero para que el e-CF referencie el id de origen
        n.setTotal(r.monto());
        n.setBalance(r.monto());
        repo.saveAndFlush(n);
        var emitido = ecf.emitir(cia, new EcfDtos.EmitirEcfRequest(TipoEcf.NOTA_DEBITO.codigo(), hoy, "01", 2,
                n.getFechaVencimiento(), comprador, List.of(item),
                new EcfDtos.Referencia(f.comprobante(), f.getFecha(), 3, n.getConcepto()), "NOTA_DEBITO", n.getId()));
        n.setTotalItbis(emitido.totalItbis());
        n.setTotal(emitido.montoTotal());
        n.setBalance(emitido.montoTotal());
        n.setEncf(emitido.numero());
        n.setEcfDocumentoId(emitido.ecfDocumentoId());
        return Response.de(n);
    }

    public Response obtener(Long cia, Long id) {
        return Response.de(repo.findByIdAndCompaniaId(id, cia).orElseThrow(() -> new NotFoundException("Nota de débito", id)));
    }
}
