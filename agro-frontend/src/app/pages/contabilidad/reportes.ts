import { Component, inject, signal } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { CurrencyPipe, DatePipe } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatTabsModule } from '@angular/material/tabs';
import type { S } from '../../api/tipos';
import { Notificacion } from '../../core/notificacion.service';
import { isoFecha, params } from '../../shared/http';
import { CuentasContables } from './cuentas-contables';

type Seccion = S['ReportesContablesSeccion'];
/** Fila plana de un estado financiero: cuentas con sangría por nivel, totales y resultados. */
interface Renglon { texto: string; nivel: number; monto: number | null; clase: '' | 'grupo' | 'total' | 'resultado' | 'titulo'; }

/** Balanza de comprobación, estado de resultados, balance general y mayor. */
import { Fecha } from '../../shared/fecha';
@Component({
  selector: 'app-reportes-contables',
  imports: [Fecha, CurrencyPipe, DatePipe, FormsModule, MatButtonModule, MatCheckboxModule, MatFormFieldModule, MatInputModule, MatSelectModule, MatTabsModule],
  template: `
    <div class="pagina">
      <div class="pagina-titulo"><h1>Estados financieros</h1><span class="espaciador"></span>
        <button mat-stroked-button (click)="imprimir()"><span class="material-symbols-outlined">print</span> Imprimir</button></div>
      <div class="tarjeta no-imprimir">
        <div class="fila">
          <app-fecha etiqueta="Desde" [(ngModel)]="desde" />
          <app-fecha etiqueta="Hasta / fecha de corte" [(ngModel)]="hasta" />
          @if (pestana === 3) {
            <mat-form-field style="min-width:320px"><mat-label>Cuenta</mat-label>
              <mat-select [(ngModel)]="cuentaMayor">
                @for (c of cuentas.todas(); track c.id) { <mat-option [value]="c.id">{{ c.codigo }} · {{ c.nombre }}</mat-option> }
              </mat-select></mat-form-field>
          }
          @if (pestana === 0) { <mat-checkbox [(ngModel)]="todas">Incluir cuentas sin movimiento</mat-checkbox> }
          <button mat-flat-button (click)="consultar()">Consultar</button>
        </div>
      </div>
      <mat-tab-group [selectedIndex]="pestana" (selectedIndexChange)="pestana = $event; consultar()">
        <mat-tab label="Balanza de comprobación">
          <div class="tarjeta">
            @if (balanza(); as b) {
              <h2 class="titulo-reporte">Balanza de comprobación del {{ b.desde | date: 'dd/MM/yyyy' }} al {{ b.hasta | date: 'dd/MM/yyyy' }}</h2>
              <div class="tabla-contenedor"><table class="tabla">
                <thead><tr><th>Cuenta</th><th class="num">Saldo anterior</th><th class="num">Débitos</th><th class="num">Créditos</th><th class="num">Saldo final</th></tr></thead>
                <tbody>
                  @for (f of b.cuentas; track f.cuentaId) {
                    <tr [class.grupo]="!f.detalle"><td [style.padding-left.px]="(f.nivel! - 1) * 16 + 6">{{ f.codigo }} · {{ f.nombre }}</td>
                      <td class="num">{{ f.saldoAnterior | currency }}</td><td class="num">{{ f.debitos | currency }}</td>
                      <td class="num">{{ f.creditos | currency }}</td><td class="num">{{ f.saldoFinal | currency }}</td></tr>
                  } @empty { <tr><td colspan="5">Sin movimientos en el período.</td></tr> }
                </tbody>
                <tfoot><tr><td>Totales (cuentas de detalle)</td><td class="num">{{ b.totalSaldoAnterior | currency }}</td>
                  <td class="num">{{ b.totalDebitos | currency }}</td><td class="num">{{ b.totalCreditos | currency }}</td>
                  <td class="num">{{ b.totalSaldoFinal | currency }}</td></tr></tfoot>
              </table></div>
              <p [class.cuadra]="b.cuadra" [class.error-texto]="!b.cuadra">{{ b.cuadra ? 'La balanza cuadra.' : 'La balanza NO cuadra.' }}
                Saldos positivos = deudores; negativos = acreedores.</p>
            }
          </div>
        </mat-tab>
        <mat-tab label="Estado de resultados">
          <div class="tarjeta">
            @if (resultados(); as r) {
              <h2 class="titulo-reporte">Estado de resultados del {{ r.desde | date: 'dd/MM/yyyy' }} al {{ r.hasta | date: 'dd/MM/yyyy' }}</h2>
              <table class="tabla estado"><tbody>
                @for (f of renglonesResultados(); track $index) {
                  <tr [class]="f.clase"><td [style.padding-left.px]="f.nivel * 16 + 6">{{ f.texto }}</td>
                    <td class="num">{{ f.monto === null ? '' : (f.monto | currency) }}</td></tr>
                }
              </tbody></table>
            }
          </div>
        </mat-tab>
        <mat-tab label="Balance general">
          <div class="tarjeta">
            @if (balance(); as g) {
              <h2 class="titulo-reporte">Balance general al {{ g.corte | date: 'dd/MM/yyyy' }}</h2>
              <table class="tabla estado"><tbody>
                @for (f of renglonesBalance(); track $index) {
                  <tr [class]="f.clase"><td [style.padding-left.px]="f.nivel * 16 + 6">{{ f.texto }}</td>
                    <td class="num">{{ f.monto === null ? '' : (f.monto | currency) }}</td></tr>
                }
              </tbody></table>
              <p [class.cuadra]="g.cuadra" [class.error-texto]="!g.cuadra">{{ g.cuadra ? 'Activos = Pasivos + Capital.' : 'El balance NO cuadra: revise asientos.' }}</p>
            }
          </div>
        </mat-tab>
        <mat-tab label="Mayor de una cuenta">
          <div class="tarjeta">
            @if (mayor(); as m) {
              <h2 class="titulo-reporte">Mayor {{ m.codigo }} · {{ m.nombre }} del {{ m.desde | date: 'dd/MM/yyyy' }} al {{ m.hasta | date: 'dd/MM/yyyy' }}</h2>
              <div class="tabla-contenedor"><table class="tabla">
                <thead><tr><th>Fecha</th><th>Asiento</th><th>Concepto</th><th class="num">Débito</th><th class="num">Crédito</th><th class="num">Saldo</th></tr></thead>
                <tbody>
                  <tr class="grupo"><td colspan="5">Saldo anterior</td><td class="num">{{ m.saldoAnterior | currency }}</td></tr>
                  @for (l of m.lineas; track $index) {
                    <tr><td>{{ l.fecha | date: 'dd/MM/yyyy' }}</td><td>{{ l.numero }}</td><td>{{ l.concepto }}<div class="sub">{{ l.descripcion }}</div></td>
                      <td class="num">{{ l.debito ? (l.debito | currency) : '' }}</td><td class="num">{{ l.credito ? (l.credito | currency) : '' }}</td>
                      <td class="num">{{ l.saldo | currency }}</td></tr>
                  }
                  <tr class="total"><td colspan="5">Saldo final (positivo = deudor)</td><td class="num">{{ m.saldoFinal | currency }}</td></tr>
                </tbody>
              </table></div>
            } @else { <p>Seleccione una cuenta y pulse Consultar.</p> }
          </div>
        </mat-tab>
      </mat-tab-group>
    </div>`,
  styles: `.tabla { width: 100%; border-collapse: collapse; td, th { padding: 6px; border-bottom: 1px solid var(--mat-sys-outline-variant); text-align: left; } }
    .tabla .num { text-align: right; white-space: nowrap; } tfoot td, .total td { font-weight: 600; border-top: 2px solid var(--mat-sys-outline); }
    .grupo td { font-weight: 600; } .titulo td { font: var(--mat-sys-title-medium); padding-top: 16px; }
    .resultado td { font: var(--mat-sys-title-medium); background: var(--mat-sys-surface-container); }
    .estado { max-width: 760px; } .sub { color: var(--mat-sys-on-surface-variant); font-size: .85em; }
    .cuadra { color: var(--mat-sys-primary); } .titulo-reporte { margin-top: 0; }
    @media print { .no-imprimir, mat-tab-header { display: none !important; } }`,
})
export class Reportes {
  private readonly http = inject(HttpClient);
  private readonly notif = inject(Notificacion);
  protected readonly cuentas = inject(CuentasContables);
  private readonly hoy = new Date();
  protected desde = isoFecha(new Date(this.hoy.getFullYear(), this.hoy.getMonth(), 1))!;
  protected hasta = isoFecha(this.hoy)!;
  protected todas = false;
  protected pestana = 0;
  protected cuentaMayor: number | null = null;
  protected readonly balanza = signal<S['ReportesContablesBalanza'] | null>(null);
  protected readonly resultados = signal<S['ReportesContablesEstadoResultados'] | null>(null);
  protected readonly balance = signal<S['ReportesContablesBalanceGeneral'] | null>(null);
  protected readonly mayor = signal<S['ReportesContablesMayor'] | null>(null);
  protected readonly renglonesResultados = signal<Renglon[]>([]);
  protected readonly renglonesBalance = signal<Renglon[]>([]);

  constructor() {
    this.cuentas.cargar();
    this.consultar();
  }

  protected consultar(): void {
    const err = (e: unknown) => this.notif.error(e);
    const rango = params({ desde: this.desde, hasta: this.hasta });
    switch (this.pestana) {
      case 0:
        this.http.get<S['ReportesContablesBalanza']>('/api/v1/contabilidad/reportes/balanza', { params: rango.set('todas', this.todas) })
          .subscribe({ next: (b) => this.balanza.set(b), error: err });
        break;
      case 1:
        this.http.get<S['ReportesContablesEstadoResultados']>('/api/v1/contabilidad/reportes/estado-resultados', { params: rango })
          .subscribe({ next: (r) => {
            this.resultados.set(r);
            this.renglonesResultados.set([...this.seccion(r.ingresos!, 'Total ingresos'), ...this.seccion(r.costos!, 'Total costos'),
              { texto: 'Utilidad bruta', nivel: 0, monto: r.utilidadBruta!, clase: 'resultado' },
              ...this.seccion(r.gastos!, 'Total gastos'),
              { texto: r.utilidadNeta! >= 0 ? 'Utilidad neta' : 'Pérdida neta', nivel: 0, monto: r.utilidadNeta!, clase: 'resultado' }]);
          }, error: err });
        break;
      case 2:
        this.http.get<S['ReportesContablesBalanceGeneral']>('/api/v1/contabilidad/reportes/balance-general', { params: params({ corte: this.hasta }) })
          .subscribe({ next: (g) => {
            this.balance.set(g);
            const pasivos = this.seccion(g.pasivos!, 'Total pasivos');
            const capital = this.seccion(g.capital!, 'Total capital', false);
            this.renglonesBalance.set([...this.seccion(g.activos!, 'Total activos'), ...pasivos, ...capital,
              { texto: 'Resultado acumulado (ingresos − costos − gastos)', nivel: 1, monto: g.resultadoAcumulado!, clase: '' },
              { texto: 'Total capital', nivel: 0, monto: (g.capital!.total ?? 0) + (g.resultadoAcumulado ?? 0), clase: 'total' },
              { texto: 'Total pasivos + capital', nivel: 0, monto: g.totalPasivoCapital!, clase: 'resultado' }]);
          }, error: err });
        break;
      case 3:
        if (!this.cuentaMayor) return;
        this.http.get<S['ReportesContablesMayor']>(`/api/v1/contabilidad/reportes/mayor/${this.cuentaMayor}`, { params: rango })
          .subscribe({ next: (m) => this.mayor.set(m), error: err });
    }
  }

  private seccion(s: Seccion, total: string, conTotal = true): Renglon[] {
    const filas: Renglon[] = [{ texto: s.titulo!, nivel: 0, monto: null, clase: 'titulo' }];
    for (const c of s.cuentas ?? []) {
      filas.push({ texto: `${c.codigo} · ${c.nombre}`, nivel: c.nivel!, monto: c.monto!, clase: c.detalle ? '' : 'grupo' });
    }
    if (conTotal) filas.push({ texto: total, nivel: 0, monto: s.total!, clase: 'total' });
    return filas;
  }

  protected imprimir(): void { window.print(); }
}
