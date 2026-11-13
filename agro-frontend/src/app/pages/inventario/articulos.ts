import { Component, computed, inject, signal } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { DatePipe, DecimalPipe, CurrencyPipe } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import type { Pagina, S } from '../../api/tipos';
import { CatalogoCrud } from '../../shared/catalogo-crud';
import { ARTICULOS } from '../catalogos/configs';
import { AuthService } from '../../core/auth.service';
import { Notificacion } from '../../core/notificacion.service';
import { isoFecha, params } from '../../shared/http';

/** Nombre legible del documento que originó el movimiento (en el kárdex se guarda como código). */
const DOCUMENTOS: Record<string, string> = { FACTURA: 'Factura', COMPRA: 'Compra', DEVOLUCION: 'Devolución', CONDUCE: 'Conduce',
  NOTA_PROVEEDOR: 'Nota de suplidor', APARTADO: 'Apartado', INV_ENTRADA: 'Entrada de inventario', INV_SALIDA: 'Salida de inventario',
  INV_AJUSTE: 'Ajuste por conteo', INV_TRANSFERENCIA: 'Transferencia', MIGRACION: 'Saldo migrado' };

const TIPOS: Record<string, string> = { SALDO_INICIAL: 'Saldo inicial', COMPRA: 'Compra', VENTA: 'Venta', DEVOLUCION_VENTA: 'Devolución de venta',
  DEVOLUCION_COMPRA: 'Devolución de compra', AJUSTE_ENTRADA: 'Ajuste (+)', AJUSTE_SALIDA: 'Ajuste (-)', ENTRADA: 'Entrada', SALIDA: 'Salida',
  TRANSFERENCIA_ENTRADA: 'Transferencia (+)', TRANSFERENCIA_SALIDA: 'Transferencia (-)' };

@Component({
  selector: 'app-articulos',
  imports: [CatalogoCrud, DatePipe, DecimalPipe, CurrencyPipe, FormsModule, MatButtonModule, MatFormFieldModule, MatInputModule, MatSelectModule],
  template: `
    <div class="pagina">
      <div class="pagina-titulo no-imprimir"><h1>Artículos</h1></div>
      <div class="tarjeta no-imprimir"><app-catalogo-crud [config]="config" [acciones]="[{ etiqueta: 'Kardex' }]" (accion)="kardex($any($event.registro))" /></div>
      @if (seleccionado(); as a) {
        <div class="tarjeta hoja" style="margin-top:16px">
          <div class="fila no-imprimir filtros">
            <h2 style="flex:1 1 100%;margin:0">Kardex · {{ a.nombre }}</h2>
            <mat-form-field><mat-label>Desde</mat-label><input matInput type="date" [(ngModel)]="f.desde" /></mat-form-field>
            <mat-form-field><mat-label>Hasta</mat-label><input matInput type="date" [(ngModel)]="f.hasta" /></mat-form-field>
            <mat-form-field><mat-label>Almacén</mat-label><mat-select [(ngModel)]="f.almacenId">
              <mat-option [value]="null">Todos</mat-option>
              @for (al of listaAlmacenes(); track al[0]) { <mat-option [value]="al[0]">{{ al[1] }}</mat-option> }</mat-select></mat-form-field>
            <button mat-flat-button (click)="consultar()"><span class="material-symbols-outlined">play_arrow</span> Consultar</button>
            <span class="espaciador"></span>
            <button mat-stroked-button [disabled]="!reporte()" (click)="imprimir()"><span class="material-symbols-outlined">print</span> Imprimir</button>
            <button mat-button (click)="seleccionado.set(null)">Cerrar</button>
          </div>

          @if (reporte(); as k) {
            <header class="encabezado">
              <div><strong>{{ empresa()?.nombre }}</strong>
                <div class="muted">RNC {{ empresa()?.rnc }}@if (empresa()?.direccion) { · {{ empresa()?.direccion }} }</div></div>
              <div class="titulo"><h2>Kardex de artículo</h2>
                <div>{{ k.desde ? (k.desde | date: 'dd/MM/yyyy') : 'Desde el inicio' }} al {{ (k.hasta ?? hoy) | date: 'dd/MM/yyyy' }}</div></div>
            </header>
            <dl class="datos">
              <div class="ancho"><dt>Artículo</dt><dd>@if (k.codigo) { {{ k.codigo }} · }{{ k.articulo }} ({{ k.unidad }})</dd></div>
              <div><dt>Almacén</dt><dd>{{ k.almacen ?? 'Todos' }}</dd></div>
              <div><dt>Costo promedio</dt><dd>{{ k.costoPromedio | currency }} por {{ k.unidad }}
                @if (k.factorEmpaque) { <div class="muted">= {{ (k.costoPromedio ?? 0) * k.factorEmpaque | currency }} por {{ k.unidadEmpaque }} de {{ k.factorEmpaque | number: '1.0-3' }} {{ k.unidad }}</div> }</dd></div>
              <div><dt>Existencia por almacén</dt><dd>@for (e of existencias(); track e.almacenId) { {{ almacen(e.almacenId) }}: {{ e.cantidad | number: '1.0-3' }}@if (!$last) { · } } @empty { 0 }</dd></div>
            </dl>
            <div class="resumen">
              <div><span>Saldo anterior</span><strong>{{ k.saldoAnterior | number: '1.0-3' }}</strong></div>
              <div><span>Entradas</span><strong class="entrada">+{{ k.entradas | number: '1.0-3' }}</strong></div>
              <div><span>Salidas</span><strong class="salida">-{{ k.salidas | number: '1.0-3' }}</strong></div>
              <div><span>Saldo final ({{ k.unidad }})</span><strong>{{ k.saldoFinal | number: '1.0-3' }}</strong>
                @if (k.factorEmpaque) { <small class="muted">= {{ equivalente(k.saldoFinal ?? 0, k) }}</small> }</div>
            </div>
            @if (k.truncado) { <p class="error-texto">Hay demasiados movimientos: se muestran los primeros 20,000. Acote el período.</p> }

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
              <thead><tr><th>Fecha</th><th>Movimiento</th><th>Documento</th><th>Almacén</th><th class="num">Entrada</th><th class="num">Salida</th>
                <th class="num">Saldo</th><th class="num">Costo x {{ k.unidad }}</th><th>Usuario</th></tr></thead>
              <tbody>
                <tr class="saldo-anterior"><td colspan="6">Saldo anterior</td><td class="num">{{ k.saldoAnterior | number: '1.0-3' }}</td><td colspan="2"></td></tr>
                @for (m of k.movimientos; track m.id) {
                  <tr><td>{{ m.fecha | date: 'dd/MM/yyyy hh:mm a' }}</td><td>{{ tipo(m.tipo) }}</td>
                    <td>{{ documento(m.documentoTipo) }} {{ m.documentoId || '' }}</td><td>{{ m.almacen }}</td>
                    <td class="num">{{ (m.cantidad ?? 0) > 0 ? (m.cantidad | number: '1.0-3') : '' }}</td>
                    <td class="num salida">{{ (m.cantidad ?? 0) < 0 ? (-(m.cantidad ?? 0) | number: '1.0-3') : '' }}</td>
                    <td class="num">{{ m.saldo | number: '1.0-3' }}</td><td class="num">{{ m.costoUnitario | currency }}</td><td>{{ m.usuario }}</td></tr>
                } @empty { <tr><td colspan="9" class="muted">Sin movimientos en el período</td></tr> }
              </tbody>
              <tfoot><tr><td colspan="4">TOTAL ({{ k.movimientos?.length ?? 0 }} movimientos)</td>
                <td class="num">{{ k.entradas | number: '1.0-3' }}</td><td class="num">{{ k.salidas | number: '1.0-3' }}</td>
                <td class="num">{{ k.saldoFinal | number: '1.0-3' }}</td><td colspan="2"></td></tr></tfoot>
            </table></div>
            <p class="pie muted">Generado el {{ ahora | date: 'dd/MM/yyyy hh:mm a' }}</p>
          } @else { <p class="muted">Cargando…</p> }
        </div>
      }
    </div>`,
  styles: `.tabla { width: 100%; border-collapse: collapse; td, th { padding: 6px 8px; border-bottom: 1px solid var(--mat-sys-outline-variant); text-align: left; } }
    .tabla .num { text-align: right; white-space: nowrap; } tfoot td { font-weight: 600; border-top: 2px solid var(--mat-sys-outline); }
    .saldo-anterior td { font-style: italic; color: var(--mat-sys-on-surface-variant); }
    .salida { color: var(--mat-sys-error); } .entrada { color: var(--mat-sys-primary); } h3 { font: var(--mat-sys-title-small); margin: 16px 0 8px; }
    .filtros { margin-bottom: 8px; mat-form-field { flex: 0 1 180px; } }
    .resumen { display: grid; grid-template-columns: repeat(auto-fit, minmax(140px, 1fr)); gap: 8px; margin: 0 0 12px;
      div { display: flex; flex-direction: column; padding: 8px 12px; border-radius: 10px; background: var(--mat-sys-surface-container); }
      span { font: var(--mat-sys-label-medium); color: var(--mat-sys-on-surface-variant); } strong { font: var(--mat-sys-title-medium); font-variant-numeric: tabular-nums; } }
    @media print { .resumen div { background: none; border: 1px solid #ccc; } }`,
})
export class Articulos {
  private readonly http = inject(HttpClient);
  private readonly notif = inject(Notificacion);
  private readonly auth = inject(AuthService);
  protected readonly config = ARTICULOS;
  protected readonly empresa = computed(() => this.auth.perfil()?.compania);
  protected readonly seleccionado = signal<S['ArticuloResponse'] | null>(null);
  protected readonly reporte = signal<S['KardexReporte'] | null>(null);
  protected readonly existencias = signal<S['ArticuloExistenciaResponse'][]>([]);
  protected readonly lotes = signal<S['LoteResponse'][]>([]);
  private readonly almacenes = signal<Map<number, string>>(new Map());
  protected readonly listaAlmacenes = computed(() => [...this.almacenes().entries()]);
  protected readonly hoy = new Date();

  /** 384.5 lb con saco de 100 → "3 Saco + 84.5 Libras"; 0.25 lb → "0.25 Libras (menos de 1 Saco)". */
  protected equivalente(cantidad: number, k: S['KardexReporte']): string {
    const f = k.factorEmpaque ?? 1;
    const signo = cantidad < 0 ? '-' : '';
    const n = Math.abs(cantidad);
    const enteros = Math.floor(n / f + 1e-9);
    const resto = Math.round((n - enteros * f) * 1000) / 1000;
    const fmt = (x: number) => x.toLocaleString('en-US', { maximumFractionDigits: 3 });
    if (enteros === 0) return `${signo}${fmt(resto)} ${k.unidad} (menos de 1 ${k.unidadEmpaque})`;
    return `${signo}${fmt(enteros)} ${k.unidadEmpaque}${resto ? ` + ${fmt(resto)} ${k.unidad}` : ''}`;
  }
  protected ahora = new Date();
  /** Por defecto, los últimos 3 meses (vaciar "Desde" trae todo el historial). */
  protected f = { desde: isoFecha(new Date(this.hoy.getFullYear(), this.hoy.getMonth() - 2, 1)) as string | null,
    hasta: isoFecha(this.hoy) as string | null, almacenId: null as number | null };

  constructor() {
    this.http.get<Pagina<{ id: number; nombre: string }>>('/api/v1/catalogos/almacenes', { params: { size: 500 } })
      .subscribe({ next: (p) => this.almacenes.set(new Map(p.content.map((a) => [a.id, a.nombre]))), error: () => {} });
  }

  protected almacen(id?: number): string { return (id != null && this.almacenes().get(id)) || (id != null ? `Almacén ${id}` : ''); }
  protected documento(t?: string): string { return DOCUMENTOS[t ?? ''] ?? t ?? ''; }
  protected tipo(t?: string): string { return TIPOS[t ?? ''] ?? t ?? ''; }

  protected kardex(a: S['ArticuloResponse']): void {
    this.seleccionado.set(a);
    this.consultar();
    this.http.get<S['ArticuloExistenciaResponse'][]>(`/api/v1/inventario/articulos/${a.id}/existencias`).subscribe((e) => this.existencias.set(e));
    this.lotes.set([]);
    if (a.manejaVencimiento) {
      this.http.get<S['LoteResponse'][]>(`/api/v1/inventario/articulos/${a.id}/lotes`).subscribe((l) => this.lotes.set(l));
    }
  }

  protected consultar(): void {
    const a = this.seleccionado();
    if (!a) return;
    this.reporte.set(null);
    this.http.get<S['KardexReporte']>(`/api/v1/inventario/articulos/${a.id}/kardex/reporte`, {
      params: params({ desde: this.f.desde || null, hasta: this.f.hasta || null, almacenId: this.f.almacenId }) })
      .subscribe({ next: (k) => { this.ahora = new Date(); this.reporte.set(k); }, error: (e) => this.notif.error(e) });
  }

  protected imprimir(): void { this.ahora = new Date(); setTimeout(() => window.print()); }
}
