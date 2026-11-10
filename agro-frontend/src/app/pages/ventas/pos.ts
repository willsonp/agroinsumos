import { Component, computed, inject, signal, viewChild } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { RouterLink } from '@angular/router';
import { CurrencyPipe } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatDialog } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatTooltipModule } from '@angular/material/tooltip';
import type { Pagina, S } from '../../api/tipos';
import { ArticuloBuscador } from '../../shared/articulo-buscador';
import { ClienteSelector } from '../../shared/cliente-selector';
import { LineaCarrito, TipoPrecio, calcular, importeConItbis, precioLista, TASAS } from '../../shared/calculo';
import { params } from '../../shared/http';
import { Notificacion } from '../../core/notificacion.service';
import { PagoDialog } from './pago-dialog';
import { TicketDialog } from './ticket-dialog';

type Cliente = S['ClienteResponse'];
type Articulo = S['ArticuloResponse'];

/**
 * Punto de venta. Reemplaza FrmFactura + FrmCaja: la venta de contado se factura, se emite el
 * e-CF y se cobra en una sola operación (POST /caja/ventas).
 */
@Component({
  selector: 'app-pos',
  imports: [RouterLink, CurrencyPipe, FormsModule, MatButtonModule, MatFormFieldModule, MatInputModule,
    MatProgressBarModule, MatTooltipModule, ArticuloBuscador, ClienteSelector],
  templateUrl: './pos.html',
  styleUrl: './pos.scss',
})
export class Pos {
  private readonly http = inject(HttpClient);
  private readonly dialog = inject(MatDialog);
  private readonly notif = inject(Notificacion);
  private readonly buscador = viewChild.required(ArticuloBuscador);

  protected readonly cliente = signal<Cliente | null>(null);
  protected readonly lineas = signal<LineaCarrito[]>([]);
  protected readonly cajaAbierta = signal<boolean | null>(null);
  protected readonly procesando = signal(false);

  protected readonly lista = computed<TipoPrecio>(() => (this.cliente()?.tipoPrecio as TipoPrecio) ?? 'CONTADO');
  protected readonly exento = computed(() => !!this.cliente()?.exentoItbis);
  protected readonly totales = computed(() => calcular(this.lineas(), this.lista(), this.exento()));
  protected readonly comprobante = computed(() => this.cliente()?.tipoEcfPreferido === 31
    ? 'Crédito fiscal (e-CF 31)' : this.cliente()?.tipoEcfPreferido === 44 ? 'Régimen especial (e-CF 44)'
    : this.cliente()?.tipoEcfPreferido === 45 ? 'Gubernamental (e-CF 45)' : 'Consumo (e-CF 32)');
  protected readonly puedeCredito = computed(() => (this.cliente()?.diasCredito ?? 0) > 0);

  protected readonly precio = (l: LineaCarrito) => precioLista(l.articulo, this.lista()) * (1 + (this.exento() ? 0 : TASAS[l.articulo.indicadorItbis ?? 4]) / 100);
  protected readonly importe = (l: LineaCarrito) => importeConItbis(l, this.lista(), this.exento());

  constructor() {
    this.verificarCaja();
    this.clientePorDefecto();
  }

  protected agregar(a: Articulo): void {
    const actual = this.lineas();
    const i = actual.findIndex((l) => l.articulo.id === a.id);
    if (i >= 0) {
      this.lineas.set(actual.map((l, j) => (j === i ? { ...l, cantidad: l.cantidad + 1 } : l)));
    } else {
      this.lineas.set([...actual, { articulo: a, cantidad: 1, descuentoPorcentaje: 0 }]);
    }
    if (a.tipo === 'B' && (a.existencia ?? 0) <= 0) this.notif.error(null, `Atención: '${a.nombre}' no tiene existencia`);
  }

  protected cambiar(i: number, campo: 'cantidad' | 'descuentoPorcentaje', valor: number): void {
    this.lineas.set(this.lineas().map((l, j) => (j === i ? { ...l, [campo]: Math.max(0, valor || 0) } : l)));
  }

  protected quitar(i: number): void {
    this.lineas.set(this.lineas().filter((_, j) => j !== i));
  }

  protected cobrar(): void {
    if (!this.validar()) return;
    this.dialog.open(PagoDialog, { data: { total: this.totales().total }, autoFocus: 'dialog' })
      .afterClosed().subscribe((pagos?: S['PagoRequest'][]) => {
        if (!pagos) return;
        this.procesando.set(true);
        this.http.post<S['VentaContadoResponse']>('/api/v1/caja/ventas', { factura: this.request('CONTADO'), pagos })
          .subscribe({
            next: (r) => this.terminar(r.factura!, r.recibo),
            error: (e) => { this.procesando.set(false); this.notif.error(e); },
          });
      });
  }

  protected venderACredito(): void {
    if (!this.validar()) return;
    this.procesando.set(true);
    this.http.post<S['FacturaResponse']>('/api/v1/ventas/facturas', this.request('CREDITO')).subscribe({
      next: (f) => this.terminar(f),
      error: (e) => { this.procesando.set(false); this.notif.error(e); },
    });
  }

  protected nuevaVenta(): void {
    this.lineas.set([]);
    this.clientePorDefecto();
    this.buscador().enfocar();
  }

  private request(condicion: 'CONTADO' | 'CREDITO'): S['FacturaCrearFacturaRequest'] {
    return {
      clienteId: this.cliente()!.id!,
      condicion,
      lineas: this.lineas().filter((l) => l.cantidad > 0).map((l) => ({
        articuloId: l.articulo.id!, cantidad: l.cantidad,
        descuentoPorcentaje: l.descuentoPorcentaje || undefined,
      })),
    };
  }

  private validar(): boolean {
    if (!this.cliente()) { this.notif.error(null, 'Seleccione el cliente'); return false; }
    if (!this.lineas().some((l) => l.cantidad > 0)) { this.notif.error(null, 'Agregue artículos a la venta'); return false; }
    return true;
  }

  private terminar(factura: S['FacturaResponse'], recibo?: S['ReciboResponse']): void {
    this.procesando.set(false);
    this.notif.ok(`Factura ${factura.numero} emitida · e-NCF ${factura.encf}`);
    this.dialog.open(TicketDialog, { data: { factura, recibo } }).afterClosed().subscribe(() => this.nuevaVenta());
  }

  private verificarCaja(): void {
    this.http.get<S['CajaSesionResponse'] | null>('/api/v1/caja/sesiones/actual').subscribe({
      next: (s) => this.cajaAbierta.set(s !== null),
      error: () => this.cajaAbierta.set(false),
    });
  }

  private clientePorDefecto(): void {
    this.http.get<Pagina<Cliente>>('/api/v1/catalogos/clientes', { params: params({ q: 'consumidor final', size: 1 }) })
      .subscribe((p) => this.cliente.set(p.content[0] ?? null));
  }
}
