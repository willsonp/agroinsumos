import { Component, inject, signal } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { DatePipe, DecimalPipe, CurrencyPipe } from '@angular/common';
import { MatButtonModule } from '@angular/material/button';
import type { Pagina, S } from '../../api/tipos';
import { CatalogoCrud } from '../../shared/catalogo-crud';
import { ARTICULOS } from '../catalogos/configs';

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
            @for (e of existencias(); track e.almacenId) { <span class="chip">Almacén {{ e.almacenId }}: {{ e.cantidad | number: '1.0-3' }}</span> }
            <button mat-button (click)="seleccionado.set(null)">Cerrar</button></div>
          <div class="tabla-contenedor"><table class="tabla">
            <tr><th>Fecha</th><th>Movimiento</th><th>Documento</th><th>Almacén</th><th class="num">Cantidad</th><th class="num">Costo</th><th class="num">Existencia</th><th>Usuario</th></tr>
            @for (m of movimientos(); track m.id) {
              <tr><td>{{ m.fecha | date: 'short' }}</td><td>{{ tipo(m.tipo) }}</td><td>{{ m.documentoTipo }} {{ m.documentoId }}</td><td>{{ m.almacenId }}</td>
                <td class="num" [class.salida]="(m.cantidad ?? 0) < 0">{{ m.cantidad | number: '1.0-3' }}</td><td class="num">{{ m.costoUnitario | currency }}</td>
                <td class="num">{{ m.existenciaFinal | number: '1.0-3' }}</td><td>{{ m.usuario }}</td></tr>
            } @empty { <tr><td colspan="8" class="muted">Sin movimientos</td></tr> }
          </table></div>
        </div>
      }
    </div>`,
  styles: `.tabla { width: 100%; border-collapse: collapse; td, th { padding: 6px 8px; border-bottom: 1px solid var(--mat-sys-outline-variant); text-align: left; } }
    .salida { color: var(--mat-sys-error); }`,
})
export class Articulos {
  private readonly http = inject(HttpClient);
  protected readonly config = ARTICULOS;
  protected readonly seleccionado = signal<S['ArticuloResponse'] | null>(null);
  protected readonly movimientos = signal<S['ArticuloKardexResponse'][]>([]);
  protected readonly existencias = signal<S['ArticuloExistenciaResponse'][]>([]);

  protected kardex(a: S['ArticuloResponse']): void {
    this.seleccionado.set(a);
    this.http.get<Pagina<S['ArticuloKardexResponse']>>(`/api/v1/inventario/articulos/${a.id}/kardex`).subscribe((p) => this.movimientos.set(p.content));
    this.http.get<S['ArticuloExistenciaResponse'][]>(`/api/v1/inventario/articulos/${a.id}/existencias`).subscribe((e) => this.existencias.set(e));
  }

  protected tipo(t?: string): string { return TIPOS[t ?? ''] ?? t ?? ''; }
}
