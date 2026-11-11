import { Component, inject, signal } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { DatePipe, DecimalPipe, CurrencyPipe } from '@angular/common';
import { MatButtonModule } from '@angular/material/button';
import type { Pagina, S } from '../../api/tipos';
import { CatalogoCrud } from '../../shared/catalogo-crud';
import { ARTICULOS } from '../catalogos/configs';

/** Nombre legible del documento que originó el movimiento (en el kárdex se guarda como código). */
const DOCUMENTOS: Record<string, string> = { FACTURA: 'Factura', COMPRA: 'Compra', DEVOLUCION: 'Devolución', CONDUCE: 'Conduce',
  NOTA_PROVEEDOR: 'Nota de suplidor', APARTADO: 'Apartado', INV_ENTRADA: 'Entrada de inventario', INV_SALIDA: 'Salida de inventario',
  INV_AJUSTE: 'Ajuste por conteo', INV_TRANSFERENCIA: 'Transferencia', MIGRACION: 'Saldo migrado' };

const TIPOS: Record<string, string> = { SALDO_INICIAL: 'Saldo inicial', COMPRA: 'Compra', VENTA: 'Venta', DEVOLUCION_VENTA: 'Devolución de venta',
  DEVOLUCION_COMPRA: 'Devolución de compra', AJUSTE_ENTRADA: 'Ajuste (+)', AJUSTE_SALIDA: 'Ajuste (-)', ENTRADA: 'Entrada', SALIDA: 'Salida',
  TRANSFERENCIA_ENTRADA: 'Transferencia (+)', TRANSFERENCIA_SALIDA: 'Transferencia (-)' };

@Component({
  selector: 'app-articulos',
  imports: [CatalogoCrud, DatePipe, DecimalPipe, CurrencyPipe, MatButtonModule],
  template: `
    <div class="pagina">
      <div class="pagina-titulo"><h1>Artículos</h1></div>
      <div class="tarjeta"><app-catalogo-crud [config]="config" [acciones]="[{ etiqueta: 'Kardex' }]" (accion)="kardex($any($event.registro))" /></div>
      @if (seleccionado(); as a) {
        <div class="tarjeta" style="margin-top:16px">
          <div class="fila"><h2 style="flex:1">Kardex · {{ a.nombre }}</h2>
            @for (e of existencias(); track e.almacenId) { <span class="chip">{{ almacen(e.almacenId) }}: {{ e.cantidad | number: '1.0-3' }}</span> }
            <button mat-button (click)="seleccionado.set(null)">Cerrar</button></div>
          @if (a.manejaVencimiento) {
            <h3>Lotes con existencia</h3>
            <div class="tabla-contenedor"><table class="tabla">
              <tr><th>Almacén</th><th>Lote</th><th>Vence</th><th>Estado</th><th class="num">Recibido</th><th class="num">Existencia</th></tr>
              @for (l of lotes(); track l.id) {
                <tr><td>{{ l.almacen }}</td><td>{{ l.numero || '—' }}</td><td>{{ l.fechaVencimiento ? (l.fechaVencimiento | date: 'dd/MM/yyyy') : 'Sin fecha' }}</td>
                  <td>@if (l.diasParaVencer == null) { <span class="chip">Sin fecha</span> }
                      @else if (l.diasParaVencer < 0) { <span class="chip error">Vencido hace {{ -l.diasParaVencer }} día(s)</span> }
                      @else if (l.diasParaVencer <= 7) { <span class="chip warn">Vence {{ l.diasParaVencer === 0 ? 'hoy' : 'en ' + l.diasParaVencer + ' día(s)' }}</span> }
                      @else { <span class="chip ok">En {{ l.diasParaVencer }} días</span> }</td>
                  <td class="num">{{ l.cantidadRecibida | number: '1.0-3' }}</td><td class="num">{{ l.existencia | number: '1.0-3' }}</td></tr>
              } @empty { <tr><td colspan="6" class="muted">Sin lotes con existencia</td></tr> }
            </table></div>
            <h3>Movimientos</h3>
          }
          <div class="tabla-contenedor"><table class="tabla">
            <tr><th>Fecha</th><th>Movimiento</th><th>Documento</th><th>Almacén</th><th class="num">Cantidad</th><th class="num">Costo</th><th class="num">Existencia</th><th>Usuario</th></tr>
            @for (m of movimientos(); track m.id) {
              <tr><td>{{ m.fecha | date: 'short' }}</td><td>{{ tipo(m.tipo) }}</td><td>{{ documento(m.documentoTipo) }} {{ m.documentoId || '' }}</td><td>{{ almacen(m.almacenId) }}</td>
                <td class="num" [class.salida]="(m.cantidad ?? 0) < 0">{{ m.cantidad | number: '1.0-3' }}</td><td class="num">{{ m.costoUnitario | currency }}</td>
                <td class="num">{{ m.existenciaFinal | number: '1.0-3' }}</td><td>{{ m.usuario }}</td></tr>
            } @empty { <tr><td colspan="8" class="muted">Sin movimientos</td></tr> }
          </table></div>
        </div>
      }
    </div>`,
  styles: `.tabla { width: 100%; border-collapse: collapse; td, th { padding: 6px 8px; border-bottom: 1px solid var(--mat-sys-outline-variant); text-align: left; } }
    .salida { color: var(--mat-sys-error); } h3 { font: var(--mat-sys-title-small); margin: 16px 0 8px; }`,
})
export class Articulos {
  private readonly http = inject(HttpClient);
  protected readonly config = ARTICULOS;
  protected readonly seleccionado = signal<S['ArticuloResponse'] | null>(null);
  protected readonly movimientos = signal<S['ArticuloKardexResponse'][]>([]);
  protected readonly existencias = signal<S['ArticuloExistenciaResponse'][]>([]);
  protected readonly lotes = signal<S['LoteResponse'][]>([]);
  private readonly almacenes = signal<Map<number, string>>(new Map());

  constructor() {
    this.http.get<Pagina<{ id: number; nombre: string }>>('/api/v1/catalogos/almacenes', { params: { size: 500 } })
      .subscribe({ next: (p) => this.almacenes.set(new Map(p.content.map((a) => [a.id, a.nombre]))), error: () => {} });
  }

  protected almacen(id?: number): string { return (id != null && this.almacenes().get(id)) || (id != null ? `Almacén ${id}` : ''); }
  protected documento(t?: string): string { return DOCUMENTOS[t ?? ''] ?? t ?? ''; }

  protected kardex(a: S['ArticuloResponse']): void {
    this.seleccionado.set(a);
    this.http.get<Pagina<S['ArticuloKardexResponse']>>(`/api/v1/inventario/articulos/${a.id}/kardex`).subscribe((p) => this.movimientos.set(p.content));
    this.http.get<S['ArticuloExistenciaResponse'][]>(`/api/v1/inventario/articulos/${a.id}/existencias`).subscribe((e) => this.existencias.set(e));
    this.lotes.set([]);
    if (a.manejaVencimiento) {
      this.http.get<S['LoteResponse'][]>(`/api/v1/inventario/articulos/${a.id}/lotes`).subscribe((l) => this.lotes.set(l));
    }
  }

  protected tipo(t?: string): string { return TIPOS[t ?? ''] ?? t ?? ''; }
}
