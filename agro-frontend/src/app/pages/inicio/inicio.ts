import { Component, computed, inject, signal } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { RouterLink } from '@angular/router';
import { DatePipe } from '@angular/common';
import { MatButtonModule } from '@angular/material/button';
import { catchError, of } from 'rxjs';
import { AuthService } from '../../core/auth.service';
import { NAVEGACION } from '../../core/navegacion';
import { colorModulo } from '../../core/colores-modulo';
import type { S } from '../../api/tipos';
import { isoFecha, params } from '../../shared/http';

type Resultado = S['ReporteResultado'];
const RAPIDOS = ['VENTAS_POS', 'VENTAS_FACTURAS', 'CXC_RECIBOS', 'INV_ARTICULOS', 'COMPRAS_REGISTRO', 'CXC_ESTADO_CUENTA', 'CAJA_SESION', 'FISCAL_FORMATOS'];

/** Inicio: saludo, indicadores del día (según los permisos del usuario), accesos rápidos y todos los módulos. */
@Component({
  selector: 'app-inicio',
  imports: [RouterLink, MatButtonModule, DatePipe],
  template: `
    <div class="pagina">
      <header class="hero">
        <div>
          <p class="fecha">{{ hoy | date: "EEEE d 'de' MMMM 'de' y" }}</p>
          <h1>{{ saludo }}, {{ nombreCorto() }}</h1>
          <p class="sub">{{ auth.perfil()?.compania?.nombre }} · resumen del día</p>
        </div>
        @if (auth.puede('VENTAS_POS')) {
          <a mat-flat-button class="cta" routerLink="/ventas/pos"><span class="material-symbols-outlined">point_of_sale</span> Nueva venta</a>
        }
      </header>

      @if (indicadores().length) {
        <section class="kpis" aria-label="Indicadores del día">
          @for (k of indicadores(); track k.titulo) {
            <a class="kpi tarjeta" [routerLink]="k.ruta" [style.--tono]="k.tono" [style.--fondo]="k.fondo">
              <span class="kpi-icono"><span class="material-symbols-outlined">{{ k.icono }}</span></span>
              <span class="kpi-texto">
                <span class="kpi-titulo">{{ k.titulo }}</span>
                <span class="kpi-valor">{{ k.valor }}</span>
                <span class="kpi-detalle">{{ k.detalle }}</span>
              </span>
            </a>
          }
        </section>
      }

      @if (rapidos().length) {
        <h2>Accesos rápidos</h2>
        <section class="rapidos">
          @for (r of rapidos(); track r.opcion.codigo) {
            <a class="rapido" [routerLink]="r.opcion.ruta" [style.--tono]="r.color.tono" [style.--fondo]="r.color.fondo">
              <span class="rapido-icono"><span class="material-symbols-outlined">{{ r.opcion.icono }}</span></span>
              <span>{{ r.opcion.nombre }}</span>
            </a>
          }
        </section>
      }

      <h2>Módulos</h2>
      <section class="modulos">
        @for (m of modulos(); track m.nombre) {
          @let c = color(m.nombre);
          <article class="modulo tarjeta" [style.--tono]="c.tono" [style.--fondo]="c.fondo">
            <header>
              <span class="modulo-icono"><span class="material-symbols-outlined">{{ m.icono }}</span></span>
              <h3>{{ m.nombre }}</h3>
              <span class="cuenta">{{ m.opciones.length }}</span>
            </header>
            <ul>
              @for (o of m.opciones; track o.codigo) {
                <li><a [routerLink]="o.ruta"><span class="material-symbols-outlined">{{ o.icono }}</span>{{ o.nombre }}
                  <span class="material-symbols-outlined flecha">arrow_forward</span></a></li>
              }
            </ul>
          </article>
        }
      </section>
    </div>`,
  styles: `
    h2 { font: var(--mat-sys-title-medium); margin: 28px 0 12px; color: var(--mat-sys-on-surface); }
    .hero { display: flex; align-items: flex-end; justify-content: space-between; gap: 16px; flex-wrap: wrap;
      padding: 24px 28px; border-radius: 20px; color: #fff;
      background: radial-gradient(circle at 85% 20%, rgb(255 255 255 / 14%), transparent 40%), linear-gradient(120deg, #0f5c2a, #2e9d4f);
      h1 { font: var(--mat-sys-headline-medium); margin: 2px 0 4px; }
      .fecha { margin: 0; opacity: .85; font: var(--mat-sys-label-large); &::first-letter { text-transform: uppercase; } }
      .sub { margin: 0; opacity: .85; } }
    .cta { --mat-button-filled-container-color: #fff; --mat-button-filled-label-text-color: #0f5c2a; }

    .kpis { display: grid; grid-template-columns: repeat(auto-fit, minmax(200px, 1fr)); gap: 16px; margin-top: 20px; }
    .kpi { display: flex; gap: 14px; align-items: center; text-decoration: none; color: inherit; transition: box-shadow .15s, transform .15s;
      &:hover { box-shadow: 0 4px 14px rgb(0 0 0 / 10%); transform: translateY(-1px); } }
    .kpi-icono, .rapido-icono, .modulo-icono { display: grid; place-items: center; flex: none; background: var(--fondo); color: var(--tono); }
    .kpi-icono { width: 48px; height: 48px; border-radius: 14px; .material-symbols-outlined { font-size: 26px; } }
    .kpi-texto { display: flex; flex-direction: column; min-width: 0; }
    .kpi-titulo { font: var(--mat-sys-label-large); color: var(--mat-sys-on-surface-variant); }
    .kpi-valor { font: var(--mat-sys-headline-small); font-variant-numeric: tabular-nums; color: var(--mat-sys-on-surface); }
    .kpi-detalle { font: var(--mat-sys-body-small); color: var(--mat-sys-on-surface-variant); }

    .rapidos { display: grid; grid-template-columns: repeat(auto-fill, minmax(150px, 1fr)); gap: 12px; }
    .rapido { display: flex; flex-direction: column; align-items: center; gap: 10px; padding: 18px 10px; border-radius: 16px;
      background: var(--mat-sys-surface); border: 1px solid var(--mat-sys-outline-variant); text-decoration: none; text-align: center;
      color: var(--mat-sys-on-surface); font: var(--mat-sys-label-large); transition: border-color .15s, box-shadow .15s;
      &:hover { border-color: var(--tono); box-shadow: 0 3px 10px rgb(0 0 0 / 8%); }
      &:focus-visible { outline: 2px solid var(--tono); outline-offset: 2px; } }
    .rapido-icono { width: 52px; height: 52px; border-radius: 16px; .material-symbols-outlined { font-size: 28px; } }

    .modulos { display: grid; grid-template-columns: repeat(auto-fill, minmax(280px, 1fr)); gap: 16px; align-items: start; }
    .modulo { padding: 0; overflow: hidden; border-top: 3px solid var(--tono);
      header { display: flex; align-items: center; gap: 12px; padding: 14px 16px 8px; }
      h3 { margin: 0; flex: 1; font: var(--mat-sys-title-small); }
      .cuenta { font: var(--mat-sys-label-small); color: var(--tono); background: var(--fondo); border-radius: 10px; padding: 2px 8px; }
      ul { list-style: none; margin: 0; padding: 0 8px 10px; }
      a { display: flex; align-items: center; gap: 10px; padding: 8px; border-radius: 10px; text-decoration: none;
        color: var(--mat-sys-on-surface); font: var(--mat-sys-body-medium);
        .material-symbols-outlined { font-size: 19px; color: var(--mat-sys-on-surface-variant); }
        .flecha { margin-left: auto; opacity: 0; transition: opacity .15s, transform .15s; color: var(--tono); }
        &:hover { background: var(--fondo); .flecha { opacity: 1; transform: translateX(2px); } }
        &:focus-visible { outline: 2px solid var(--tono); outline-offset: -2px; } } }
    .modulo-icono { width: 38px; height: 38px; border-radius: 11px; .material-symbols-outlined { font-size: 21px; } }
    @media (max-width: 600px) { .hero { padding: 18px; h1 { font: var(--mat-sys-headline-small); } } }`,
})
export class Inicio {
  protected readonly auth = inject(AuthService);
  private readonly http = inject(HttpClient);
  protected readonly color = colorModulo;
  protected readonly hoy = new Date();
  protected readonly saludo = this.hoy.getHours() < 12 ? 'Buenos días' : this.hoy.getHours() < 19 ? 'Buenas tardes' : 'Buenas noches';
  protected readonly nombreCorto = computed(() => (this.auth.perfil()?.nombre ?? '').trim().split(/\s+/)[0]);

  protected readonly modulos = computed(() => {
    this.auth.perfil();
    return NAVEGACION.map((m) => ({ ...m, opciones: m.opciones.filter((o) => this.auth.puede(o.codigo)) })).filter((m) => m.opciones.length);
  });
  protected readonly rapidos = computed(() => this.modulos()
    .flatMap((m) => m.opciones.map((o) => ({ opcion: o, color: colorModulo(m.nombre) })))
    .filter((x) => RAPIDOS.includes(x.opcion.codigo))
    .sort((a, b) => RAPIDOS.indexOf(a.opcion.codigo) - RAPIDOS.indexOf(b.opcion.codigo)));

  private readonly ventas = signal<Resultado | null>(null);
  private readonly vencidas = signal<Resultado | null>(null);
  private readonly bajoMinimo = signal<Resultado | null>(null);
  private readonly porVencer = signal<Resultado | null>(null);
  private readonly caja = signal<S['CajaSesionResponse'] | null | undefined>(undefined);

  private readonly dinero = new Intl.NumberFormat('es-DO', { style: 'currency', currency: 'DOP' });

  /** Solo los indicadores que el usuario puede ver (cada uno con su propio permiso). */
  protected readonly indicadores = computed(() => {
    const k: { titulo: string; valor: string; detalle: string; icono: string; ruta: string; tono: string; fondo: string }[] = [];
    const v = this.ventas();
    if (v) {
      const c = colorModulo('Ventas');
      k.push({ titulo: 'Ventas de hoy', valor: this.dinero.format(Number(v.totales?.['total'] ?? 0)),
        detalle: `${Number(v.totales?.['facturas'] ?? 0)} factura(s) · contado ${this.dinero.format(Number(v.totales?.['contado'] ?? 0))}`,
        icono: 'trending_up', ruta: '/reportes/ventas', ...c });
    }
    const caja = this.caja();
    if (caja !== undefined) {
      const c = colorModulo('Caja');
      k.push(caja ? { titulo: 'Mi caja', valor: this.dinero.format(Number(caja.cuadre?.efectivoEsperado ?? 0)),
          detalle: `Caja #${caja.numero} abierta · efectivo esperado`, icono: 'account_balance_wallet', ruta: '/caja', ...c }
        : { titulo: 'Mi caja', valor: 'Cerrada', detalle: 'Ábrala para empezar a vender', icono: 'lock', ruta: '/caja', ...c });
    }
    const ve = this.vencidas();
    if (ve) {
      const c = colorModulo('Cuentas por Cobrar');
      k.push({ titulo: 'Cuentas vencidas', valor: this.dinero.format(Number(ve.totales?.['balance'] ?? 0)),
        detalle: `${ve.filas?.length ?? 0} documento(s) por cobrar vencidos`, icono: 'event_busy', ruta: '/reportes/cobros', ...c });
    }
    const bm = this.bajoMinimo();
    if (bm) {
      const c = colorModulo('Inventario');
      k.push({ titulo: 'Bajo el mínimo', valor: String(bm.filas?.length ?? 0),
        detalle: 'artículo(s) por reponer', icono: 'inventory', ruta: '/reportes/inventario', ...c });
    }
    const pv = this.porVencer();
    if (pv && pv.filas?.length) {
      const vencidos = pv.filas.filter((f) => (f as Record<string, unknown>)['estado'] === 'VENCIDO').length;
      k.push({ titulo: 'Por vencer (7 días)', valor: String(pv.filas.length),
        detalle: vencidos ? `lote(s) · ${vencidos} ya vencido(s)` : `lote(s) · valor ${this.dinero.format(Number(pv.totales?.['valor'] ?? 0))}`,
        icono: 'event_upcoming', ruta: '/reportes/inventario', tono: '#b3261e', fondo: 'light-dark(#ffdad6, #5c1a16)' });
    }
    return k;
  });

  constructor() {
    const hoy = isoFecha(this.hoy);
    const reporte = (codigo: string, p: Record<string, unknown>, destino: { set: (r: Resultado | null) => void }) =>
      this.http.get<Resultado>(`/api/v1/reportes/${codigo}`, { params: params(p) }).pipe(catchError(() => of(null)))
        .subscribe((r) => destino.set(r));
    if (this.auth.puede('REP_VENTAS')) reporte('ventas-por-fecha', { desde: hoy, hasta: hoy }, this.ventas);
    if (this.auth.puede('REP_CXC_CAJA')) reporte('facturas-vencidas', { corte: hoy }, this.vencidas);
    if (this.auth.puede('REP_INVENTARIO')) {
      reporte('bajo-minimo', {}, this.bajoMinimo);
      reporte('productos-por-vencer', { dias: 7 }, this.porVencer);
    }
    if (this.auth.puede('CAJA_SESION')) {
      this.http.get<S['CajaSesionResponse'] | null>('/api/v1/caja/sesiones/actual').pipe(catchError(() => of(null)))
        .subscribe((c) => this.caja.set(c));
    }
  }
}
