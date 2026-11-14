import { Component, computed, inject, input, signal } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { DatePipe } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import type { Pagina, S } from '../../api/tipos';
import { Notificacion } from '../../core/notificacion.service';
import { ClienteSelector } from '../../shared/cliente-selector';
import { descargar, isoFecha, params } from '../../shared/http';

type Info = S['ReporteInfo'];
type Resultado = S['ReporteResultado'];
interface Opcion { id: number; nombre: string; }

const GRUPOS: Record<string, { grupo: string; titulo: string }> = {
  ventas: { grupo: 'VENTAS', titulo: 'Reportes de ventas' },
  cobros: { grupo: 'CXC_CAJA', titulo: 'Reportes de cobros y caja' },
  inventario: { grupo: 'INVENTARIO', titulo: 'Reportes de inventario' },
  compras: { grupo: 'COMPRAS', titulo: 'Reportes de compras' },
};

/** Visor genérico: lista de reportes del grupo, filtros que pide cada uno, tabla con totales, impresión y Excel. */
import { Fecha } from '../../shared/fecha';
@Component({
  selector: 'app-reportes',
  imports: [Fecha, DatePipe, FormsModule, MatButtonModule, MatCheckboxModule, MatFormFieldModule, MatInputModule,
    MatSelectModule, ClienteSelector],
  template: `
    <div class="pagina">
      <div class="pagina-titulo no-imprimir"><h1>{{ config().titulo }}</h1></div>
      <div class="contenedor">
        <nav class="lista no-imprimir tarjeta" aria-label="Reportes">
          @for (r of delGrupo(); track r.codigo) {
            <button class="item" [class.activo]="actual()?.codigo === r.codigo" (click)="elegir(r)">
              <strong>{{ r.titulo }}</strong><span>{{ r.descripcion }}</span></button>
          } @empty { <p class="muted">No tiene reportes de este grupo asignados.</p> }
        </nav>

        <section class="detalle">
          @if (actual(); as r) {
            <div class="tarjeta no-imprimir">
              <div class="fila">
                @if (pide('RANGO_FECHAS')) {
                  <app-fecha etiqueta="Desde" [(ngModel)]="f.desde" />
                  <app-fecha etiqueta="Hasta" [(ngModel)]="f.hasta" />
                }
                @if (pide('FECHA_CORTE')) { <app-fecha etiqueta="Al" [(ngModel)]="f.corte" /> }
                @if (pide('CLIENTE')) { <div style="flex:1 1 260px"><app-cliente-selector [(cliente)]="cliente" etiqueta="Cliente (todos si vacío)" /></div> }
                @if (pide('VENDEDOR')) { <mat-form-field><mat-label>Vendedor</mat-label><mat-select [(ngModel)]="f.vendedorId">
                  <mat-option [value]="null">Todos</mat-option>@for (o of vendedores(); track o.id) { <mat-option [value]="o.id">{{ o.nombre }}</mat-option> }</mat-select></mat-form-field> }
                @if (pide('DEPARTAMENTO')) { <mat-form-field><mat-label>Departamento</mat-label><mat-select [(ngModel)]="f.departamentoId">
                  <mat-option [value]="null">Todos</mat-option>@for (o of departamentos(); track o.id) { <mat-option [value]="o.id">{{ o.nombre }}</mat-option> }</mat-select></mat-form-field> }
                @if (pide('ALMACEN')) { <mat-form-field><mat-label>Almacén</mat-label><mat-select [(ngModel)]="f.almacenId">
                  <mat-option [value]="null">Todos</mat-option>@for (o of almacenes(); track o.id) { <mat-option [value]="o.id">{{ o.nombre }}</mat-option> }</mat-select></mat-form-field> }
                @if (pide('PROVEEDOR')) { <mat-form-field><mat-label>Suplidor</mat-label><mat-select [(ngModel)]="f.proveedorId">
                  <mat-option [value]="null">Todos</mat-option>@for (o of proveedores(); track o.id) { <mat-option [value]="o.id">{{ o.nombre }}</mat-option> }</mat-select></mat-form-field> }
                @if (pide('PROVEEDOR_REQUERIDO')) { <mat-form-field style="min-width:260px"><mat-label>Suplidor</mat-label><mat-select [(ngModel)]="f.proveedorId">
                  @for (o of proveedores(); track o.id) { <mat-option [value]="o.id">{{ o.nombre }}</mat-option> }</mat-select></mat-form-field> }
                @if (pide('DIAS')) { <mat-form-field style="width:170px"><mat-label>Próximos días</mat-label>
                  <input matInput type="number" min="0" max="365" [(ngModel)]="f.dias" /><mat-hint>0 = solo vencidos y hoy</mat-hint></mat-form-field> }
                @if (pide('CON_EXISTENCIA')) { <mat-checkbox [(ngModel)]="f.conExistencia">Solo con existencia</mat-checkbox> }
                <span class="espaciador"></span>
                <button mat-flat-button [disabled]="faltaSuplidor()" (click)="consultar()"><span class="material-symbols-outlined">play_arrow</span> Consultar</button>
              </div>
            </div>

            @if (resultado(); as res) {
              <div class="tarjeta hoja">
                <header class="encabezado">
                  <div><strong>{{ empresa()?.nombreComercial || empresa()?.nombre }}</strong>
                    <div class="muted">RNC {{ empresa()?.rnc }}@if (empresa()?.direccion) { · {{ empresa()?.direccion }} }@if (empresa()?.telefono) { · Tel. {{ empresa()?.telefono }} }</div></div>
                  <div class="titulo"><h2>{{ res.titulo }}</h2><div>{{ res.subtitulo }}@if (filtrosTexto()) { · {{ filtrosTexto() }} }</div></div>
                  <div class="acciones no-imprimir">
                    <button mat-stroked-button (click)="imprimir()"><span class="material-symbols-outlined">print</span> Imprimir</button>
                    <button mat-stroked-button (click)="excel()"><span class="material-symbols-outlined">table_view</span> Excel</button>
                  </div>
                </header>
                <div class="tabla-contenedor">
                  <table class="tabla">
                    <thead><tr>@for (c of res.columnas; track c.clave) { <th [class.num]="esNumero(c)">{{ c.titulo }}</th> }</tr></thead>
                    <tbody>
                      @for (fila of res.filas; track $index) {
                        <tr>@for (c of res.columnas; track c.clave) { <td [class.num]="esNumero(c)">{{ valor(c, fila[c.clave!]) }}</td> }</tr>
                      } @empty { <tr><td [attr.colspan]="res.columnas?.length" class="muted">Sin datos para los filtros elegidos</td></tr> }
                    </tbody>
                    @if (hayTotales(res)) {
                      <tfoot><tr>@for (c of res.columnas; track c.clave; let i = $index) {
                        <td [class.num]="esNumero(c)">{{ i === 0 ? 'TOTAL (' + (res.filas?.length ?? 0) + ')' : valor(c, res.totales?.[c.clave!]) }}</td> }</tr></tfoot>
                    }
                  </table>
                </div>
                <p class="pie muted">Generado el {{ ahora | date: 'dd/MM/yyyy hh:mm a' }}</p>
              </div>
            }
          } @else { <div class="tarjeta"><p>Elija un reporte de la lista.</p></div> }
        </section>
      </div>
    </div>`,
  styles: `.contenedor { display: grid; grid-template-columns: minmax(220px, 300px) 1fr; gap: 16px; align-items: start; }
    @media (max-width: 900px) { .contenedor { grid-template-columns: 1fr; } }
    .lista { display: flex; flex-direction: column; gap: 4px; padding: 8px; }
    .item { text-align: left; border: 0; background: none; padding: 10px; border-radius: 8px; cursor: pointer; color: inherit; font: inherit;
      display: flex; flex-direction: column; gap: 2px; span { font-size: .82em; color: var(--mat-sys-on-surface-variant); } }
    .item:hover { background: var(--mat-sys-surface-container); } .item.activo { background: var(--mat-sys-secondary-container); }
    .detalle { min-width: 0; display: flex; flex-direction: column; gap: 16px; }
    .encabezado { display: flex; gap: 16px; align-items: flex-start; flex-wrap: wrap; margin-bottom: 12px;
      .titulo { flex: 1; text-align: center; h2 { margin: 0; } } }
    .tabla { width: 100%; border-collapse: collapse; font-size: 13px; td, th { padding: 5px 6px; border-bottom: 1px solid var(--mat-sys-outline-variant); text-align: left; } }
    .tabla .num { text-align: right; white-space: nowrap; } tfoot td { font-weight: 600; border-top: 2px solid var(--mat-sys-outline); }
    .pie { margin-top: 8px; font-size: .8em; }
    @media print { .contenedor { display: block; } .hoja { box-shadow: none !important; padding: 0 !important; }
      .tabla { font-size: 10px; } .tabla-contenedor { overflow: visible !important; } }`,
})
export class Reportes {
  private readonly http = inject(HttpClient);
  private readonly notif = inject(Notificacion);
  /** Grupo desde la ruta: ventas, cobros, inventario o compras. */
  readonly grupo = input<string>('ventas');
  protected readonly config = computed(() => GRUPOS[this.grupo()] ?? GRUPOS['ventas']);
  private readonly todos = signal<Info[]>([]);
  protected readonly delGrupo = computed(() => this.todos().filter((r) => r.grupo === this.config().grupo));
  protected readonly actual = signal<Info | null>(null);
  protected readonly resultado = signal<Resultado | null>(null);
  protected readonly empresa = signal<S['CompaniaResponse'] | null>(null);
  protected readonly vendedores = signal<Opcion[]>([]);
  protected readonly departamentos = signal<Opcion[]>([]);
  protected readonly almacenes = signal<Opcion[]>([]);
  protected readonly proveedores = signal<Opcion[]>([]);
  protected readonly cliente = signal<S['ClienteResponse'] | null>(null);
  protected ahora = new Date();
  private readonly hoy = new Date();
  protected f = { desde: isoFecha(new Date(this.hoy.getFullYear(), this.hoy.getMonth(), 1))!, hasta: isoFecha(this.hoy)!, corte: isoFecha(this.hoy)!,
    vendedorId: null as number | null, departamentoId: null as number | null, almacenId: null as number | null,
    proveedorId: null as number | null, conExistencia: false, dias: 7 };
  private readonly moneda = new Intl.NumberFormat('es-DO', { minimumFractionDigits: 2, maximumFractionDigits: 2 });
  private readonly numero = new Intl.NumberFormat('es-DO', { maximumFractionDigits: 3 });

  constructor() {
    this.http.get<Info[]>('/api/v1/reportes').subscribe({ next: (r) => this.todos.set(r), error: (e) => this.notif.error(e) });
    this.http.get<S['CompaniaResponse']>('/api/v1/admin/empresa').subscribe((e) => this.empresa.set(e));
    const cat = (url: string, destino: { set: (v: Opcion[]) => void }) =>
      this.http.get<Pagina<Opcion & { estado?: string }>>(url, { params: params({ size: 500, sort: 'nombre' }) })
        .subscribe({ next: (p) => destino.set(p.content.filter((x) => x.estado !== 'I')), error: () => destino.set([]) });
    cat('/api/v1/catalogos/vendedores', this.vendedores);
    cat('/api/v1/catalogos/departamentos', this.departamentos);
    cat('/api/v1/catalogos/almacenes', this.almacenes);
    cat('/api/v1/catalogos/proveedores', this.proveedores);
  }

  protected pide(filtro: string): boolean { return (this.actual()?.filtros ?? []).includes(filtro as never); }

  /** Reportes que exigen un suplidor (estado de cuenta) no se ejecutan hasta elegirlo. */
  protected faltaSuplidor(): boolean { return this.pide('PROVEEDOR_REQUERIDO') && !this.f.proveedorId; }

  protected elegir(r: Info): void { this.actual.set(r); this.resultado.set(null); if (!this.faltaSuplidor()) this.consultar(); }

  private parametros() {
    const p: Record<string, unknown> = {};
    if (this.pide('RANGO_FECHAS')) { p['desde'] = this.f.desde; p['hasta'] = this.f.hasta; }
    if (this.pide('FECHA_CORTE')) p['corte'] = this.f.corte;
    if (this.pide('CLIENTE')) p['clienteId'] = this.cliente()?.id;
    if (this.pide('VENDEDOR')) p['vendedorId'] = this.f.vendedorId;
    if (this.pide('DEPARTAMENTO')) p['departamentoId'] = this.f.departamentoId;
    if (this.pide('ALMACEN')) p['almacenId'] = this.f.almacenId;
    if (this.pide('PROVEEDOR') || this.pide('PROVEEDOR_REQUERIDO')) p['proveedorId'] = this.f.proveedorId;
    if (this.pide('CON_EXISTENCIA')) p['conExistencia'] = this.f.conExistencia;
    if (this.pide('DIAS')) p['dias'] = Math.min(365, Math.max(0, Math.round(this.f.dias || 0)));
    return params(p);
  }

  protected consultar(): void {
    const r = this.actual();
    if (!r || this.faltaSuplidor()) return;
    this.http.get<Resultado>(`/api/v1/reportes/${r.codigo}`, { params: this.parametros() }).subscribe({
      next: (res) => { this.ahora = new Date(); this.resultado.set(res); }, error: (e) => this.notif.error(e) });
  }

  /** Filtros elegidos en texto (para el encabezado impreso). */
  protected filtrosTexto(): string {
    const t: string[] = [];
    const nombre = (lista: Opcion[], id: number | null) => lista.find((o) => o.id === id)?.nombre;
    if (this.pide('CLIENTE') && this.cliente()) t.push('Cliente: ' + this.cliente()!.nombre);
    if (this.pide('VENDEDOR') && this.f.vendedorId) t.push('Vendedor: ' + nombre(this.vendedores(), this.f.vendedorId));
    if (this.pide('DEPARTAMENTO') && this.f.departamentoId) t.push('Departamento: ' + nombre(this.departamentos(), this.f.departamentoId));
    if (this.pide('ALMACEN') && this.f.almacenId) t.push('Almacén: ' + nombre(this.almacenes(), this.f.almacenId));
    if ((this.pide('PROVEEDOR') || this.pide('PROVEEDOR_REQUERIDO')) && this.f.proveedorId) t.push('Suplidor: ' + nombre(this.proveedores(), this.f.proveedorId));
    if (this.pide('CON_EXISTENCIA') && this.f.conExistencia) t.push('solo con existencia');
    return t.join(' · ');
  }

  protected esNumero(c: S['ReporteColumna']): boolean { return ['ENTERO', 'NUMERO', 'MONEDA', 'PORCENTAJE'].includes(c.tipo ?? ''); }
  protected hayTotales(r: Resultado): boolean { return Object.keys(r.totales ?? {}).length > 0; }

  protected valor(c: S['ReporteColumna'], v: unknown): string {
    if (v === null || v === undefined || v === '') return '';
    switch (c.tipo) {
      case 'MONEDA': return this.moneda.format(Number(v));
      case 'NUMERO': return this.numero.format(Number(v));
      case 'ENTERO': return String(Math.round(Number(v)));
      case 'PORCENTAJE': return this.moneda.format(Number(v)) + '%';
      case 'FECHA': { const [a, m, d] = String(v).split('-'); return d ? `${d}/${m}/${a}` : String(v); }
      default: return String(v);
    }
  }

  protected imprimir(): void { window.print(); }

  protected excel(): void {
    const r = this.actual()!;
    this.http.get(`/api/v1/reportes/${r.codigo}/csv`, { params: this.parametros(), responseType: 'blob' })
      .subscribe({ next: (b) => descargar(b, `${r.codigo}.csv`), error: (e) => this.notif.error(e) });
  }
}
