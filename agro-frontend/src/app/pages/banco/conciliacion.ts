import { Component, computed, inject, signal } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { CurrencyPipe, DatePipe } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MatDialog } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import type { S } from '../../api/tipos';
import { Notificacion } from '../../core/notificacion.service';
import { ConfirmarDialog } from '../../shared/confirmar-dialog';
import { CuentasBancarias } from '../../shared/cuentas-bancarias';
import { isoFecha, redondear } from '../../shared/http';
import { TIPOS_MOVIMIENTO } from './movimientos';

type Resultado = S['ConciliacionResultado'];
type Mov = S['MovimientoBancarioResponse'];

/**
 * Conciliación bancaria (legacy conciliacionbancaria): se marcan los movimientos que aparecen en el estado
 * de cuenta del banco; los no marcados quedan en tránsito. Solo se cierra si la diferencia es cero.
 */
import { Fecha } from '../../shared/fecha';
@Component({
  selector: 'app-conciliacion',
  imports: [Fecha, CurrencyPipe, DatePipe, FormsModule, MatButtonModule, MatCheckboxModule, MatFormFieldModule, MatInputModule, MatSelectModule],
  template: `
    <div class="pagina">
      <div class="pagina-titulo"><h1>Conciliación bancaria</h1></div>
      <div class="tarjeta">
        <div class="fila">
          <mat-form-field style="flex:1 1 300px"><mat-label>Cuenta</mat-label><mat-select [(ngModel)]="cuentaId" (selectionChange)="historial()">
            @for (c of cuentas.activas(); track c.id) { <mat-option [value]="c.id">{{ etiqueta(c) }}</mat-option> }
          </mat-select></mat-form-field>
          <app-fecha etiqueta="Fecha de corte" [(ngModel)]="fechaCorte" />
          <mat-form-field><mat-label>Saldo según el banco</mat-label>
            <input matInput type="number" [ngModel]="saldoBanco()" (ngModelChange)="saldoBanco.set(+$event || 0)" /></mat-form-field>
          <button mat-flat-button [disabled]="!cuentaId || !fechaCorte" (click)="cargar()">Cargar movimientos</button>
        </div>
      </div>

      @if (resultado(); as r) {
        <div class="tarjeta">
          <div class="resumen">
            <div>Saldo en libros<strong>{{ r.saldoLibros | currency }}</strong></div>
            <div>(−) Depósitos en tránsito<strong>{{ calculo().depositos | currency }}</strong></div>
            <div>(+) Cheques/débitos en tránsito<strong>{{ calculo().cheques | currency }}</strong></div>
            <div>Saldo conciliado<strong>{{ calculo().conciliado | currency }}</strong></div>
            <div [class.cuadra]="calculo().diferencia === 0" [class.no-cuadra]="calculo().diferencia !== 0">
              Diferencia<strong>{{ calculo().diferencia | currency }}</strong></div>
          </div>
          @if (r.ultimaConciliacion) { <p class="ayuda">Última conciliación al {{ r.ultimaConciliacion | date: 'dd/MM/yyyy' }}.</p> }
          <p class="ayuda">Marque los movimientos que aparecen en el estado de cuenta del banco. Los no marcados quedan en tránsito.</p>
          <div class="fila">
            <button mat-button (click)="marcarTodos(true)">Marcar todos</button>
            <button mat-button (click)="marcarTodos(false)">Desmarcar todos</button>
          </div>
          <div class="tabla-contenedor">
            <table class="tabla">
              <thead><tr><th></th><th>Fecha</th><th>Tipo</th><th>Ref.</th><th>Concepto</th><th class="num">Monto</th></tr></thead>
              <tbody>
                @for (m of r.pendientes ?? []; track m.id) {
                  <tr (click)="alternar(m)" [class.marcado]="marcados().has(m.id!)">
                    <td><mat-checkbox [checked]="marcados().has(m.id!)" (click)="$event.preventDefault()" [aria-label]="'Movimiento ' + m.numero" /></td>
                    <td>{{ m.fecha | date: 'dd/MM/yyyy' }}</td><td>{{ tipos[m.tipo!] }}</td><td>{{ m.referencia }}</td>
                    <td>{{ m.concepto }}</td><td class="num" [class.negativo]="(m.montoConSigno ?? 0) < 0">{{ m.montoConSigno | currency }}</td>
                  </tr>
                } @empty { <tr><td colspan="6">No hay movimientos pendientes hasta la fecha de corte.</td></tr> }
              </tbody>
            </table>
          </div>
          <div class="fila">
            <mat-form-field style="flex:1"><mat-label>Observación</mat-label><input matInput [(ngModel)]="observacion" maxlength="200" /></mat-form-field>
            <button mat-flat-button [disabled]="calculo().diferencia !== 0" (click)="conciliar()">Cerrar conciliación</button>
          </div>
        </div>
      }

      @if (anteriores().length) {
        <h2>Conciliaciones anteriores</h2>
        <div class="tarjeta tabla-contenedor">
          <table class="tabla">
            <thead><tr><th>Corte</th><th class="num">Saldo banco</th><th class="num">Saldo libros</th>
              <th class="num">Dep. tránsito</th><th class="num">Cheq. tránsito</th><th>Observación</th></tr></thead>
            <tbody>
              @for (c of anteriores(); track c.id) {
                <tr><td>{{ c.fechaCorte | date: 'dd/MM/yyyy' }}</td><td class="num">{{ c.saldoBanco | currency }}</td>
                  <td class="num">{{ c.saldoLibros | currency }}</td><td class="num">{{ c.depositosTransito | currency }}</td>
                  <td class="num">{{ c.chequesTransito | currency }}</td><td>{{ c.observacion }}</td></tr>
              }
            </tbody>
          </table>
        </div>
      }
    </div>`,
  styles: `.tabla { width: 100%; border-collapse: collapse; td, th { padding: 6px; border-bottom: 1px solid var(--mat-sys-outline-variant); text-align: left; } }
    .tabla td.num, .tabla th.num { text-align: right; } tbody tr { cursor: pointer; } tr.marcado { background: var(--mat-sys-surface-container); }
    .resumen { display: flex; gap: 24px; flex-wrap: wrap; div { display: flex; flex-direction: column; } strong { font: var(--mat-sys-title-large); } }
    .cuadra strong { color: var(--mat-sys-primary); } .no-cuadra strong, .negativo { color: var(--mat-sys-error); }
    .ayuda { color: var(--mat-sys-on-surface-variant); }`,
})
export class Conciliacion {
  private readonly http = inject(HttpClient);
  private readonly dialog = inject(MatDialog);
  private readonly notif = inject(Notificacion);
  protected readonly cuentas = inject(CuentasBancarias);
  protected readonly etiqueta = CuentasBancarias.etiqueta;
  protected readonly tipos = TIPOS_MOVIMIENTO;
  protected cuentaId: number | null = null;
  protected fechaCorte = isoFecha(new Date())!;
  protected observacion = '';
  protected readonly saldoBanco = signal(0);
  protected readonly resultado = signal<Resultado | null>(null);
  protected readonly marcados = signal(new Set<number>());
  protected readonly anteriores = signal<S['ConciliacionResponse'][]>([]);

  /** Recalcula en el navegador con la misma fórmula del backend (que vuelve a validar al cerrar). */
  protected readonly calculo = computed(() => {
    const r = this.resultado();
    let depositos = 0, cheques = 0;
    for (const m of r?.pendientes ?? []) {
      if (this.marcados().has(m.id!)) continue;
      if ((m.montoConSigno ?? 0) > 0) depositos += m.monto ?? 0; else cheques += m.monto ?? 0;
    }
    const conciliado = redondear((r?.saldoLibros ?? 0) - depositos + cheques);
    return { depositos: redondear(depositos), cheques: redondear(cheques), conciliado, diferencia: redondear(this.saldoBanco() - conciliado) };
  });

  constructor() {
    this.cuentas.cargar();
  }

  private peticion(): S['ConciliacionRequest'] {
    return { cuentaId: this.cuentaId!, fechaCorte: this.fechaCorte, saldoBanco: this.saldoBanco(),
      movimientoIds: [...this.marcados()], observacion: this.observacion || undefined };
  }

  protected cargar(): void {
    this.marcados.set(new Set());
    this.http.post<Resultado>('/api/v1/banco/conciliaciones/calcular', this.peticion()).subscribe({
      next: (r) => this.resultado.set(r), error: (e) => { this.resultado.set(null); this.notif.error(e); } });
    this.historial();
  }

  protected historial(): void {
    if (!this.cuentaId) return;
    this.http.get<S['ConciliacionResponse'][]>(`/api/v1/banco/cuentas/${this.cuentaId}/conciliaciones`)
      .subscribe({ next: (h) => this.anteriores.set(h), error: () => this.anteriores.set([]) });
  }

  protected alternar(m: Mov): void {
    const s = new Set(this.marcados());
    if (s.has(m.id!)) s.delete(m.id!); else s.add(m.id!);
    this.marcados.set(s);
  }

  protected marcarTodos(si: boolean): void {
    this.marcados.set(new Set(si ? (this.resultado()?.pendientes ?? []).map((m) => m.id!) : []));
  }

  protected conciliar(): void {
    this.dialog.open(ConfirmarDialog, { data: { titulo: 'Cerrar conciliación', accion: 'Conciliar',
      mensaje: `Los movimientos marcados quedan conciliados al ${this.fechaCorte} y ya no se podrán anular ni registrar movimientos con fecha igual o anterior.` } })
      .afterClosed().subscribe((ok) => ok && this.http.post<S['ConciliacionResponse']>('/api/v1/banco/conciliaciones', this.peticion()).subscribe({
        next: () => { this.notif.ok('Conciliación cerrada'); this.resultado.set(null); this.observacion = ''; this.historial(); },
        error: (e) => this.notif.error(e) }));
  }
}
