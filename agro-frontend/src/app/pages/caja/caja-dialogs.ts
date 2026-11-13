import { Component, computed, inject, signal } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { CurrencyPipe } from '@angular/common';
import { FormsModule, NonNullableFormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatButtonModule } from '@angular/material/button';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import type { S } from '../../api/tipos';
import { redondear } from '../../shared/http';
import { ComprobanteService } from '../../core/comprobante.service';

/** Egreso de caja chica. "Gasto menor" emite e-CF 43 cuando el proveedor no entrega comprobante. */
@Component({
  selector: 'app-egreso-dialog',
  imports: [MatDialogModule, MatButtonModule, MatCheckboxModule, MatFormFieldModule, MatInputModule, ReactiveFormsModule],
  template: `
    <h2 mat-dialog-title>{{ ingreso ? 'Ingreso a caja' : 'Egreso de caja' }}</h2>
    <mat-dialog-content [formGroup]="form" class="grid-form" style="min-width:min(520px,90vw)">
      @if (!ingreso) {
        <mat-form-field class="ancho-completo"><mat-label>Beneficiario</mat-label><input matInput formControlName="beneficiario" /></mat-form-field>
      }
      <mat-form-field class="ancho-completo"><mat-label>Concepto</mat-label><input matInput formControlName="concepto" /></mat-form-field>
      <mat-form-field><mat-label>Monto</mat-label><input matInput type="number" formControlName="monto" /></mat-form-field>
      @if (!ingreso && comp.conComprobante()) {
        <mat-checkbox class="ancho-completo" formControlName="emitirGastoMenor">
          Sin comprobante del proveedor: emitir {{ comp.tipo(43) }} (gasto menor)
        </mat-checkbox>
        @if (!form.controls.emitirGastoMenor.value) {
          <mat-form-field><mat-label>RNC del proveedor</mat-label><input matInput formControlName="rnc" /></mat-form-field>
          <mat-form-field><mat-label>NCF del proveedor</mat-label><input matInput formControlName="ncf" /></mat-form-field>
          <mat-form-field><mat-label>ITBIS incluido</mat-label><input matInput type="number" formControlName="itbis" /></mat-form-field>
        }
      }
    </mat-dialog-content>
    <mat-dialog-actions align="end">
      <button mat-button mat-dialog-close>Cancelar</button>
      <button mat-flat-button [disabled]="form.invalid" (click)="guardar()">Registrar</button>
    </mat-dialog-actions>`,
})
export class EgresoDialog {
  protected readonly comp = inject(ComprobanteService);
  protected readonly ingreso = inject<{ ingreso: boolean }>(MAT_DIALOG_DATA).ingreso;
  private readonly ref = inject(MatDialogRef<EgresoDialog>);
  protected readonly form = inject(NonNullableFormBuilder).group({
    beneficiario: ['', this.ingreso ? [] : [Validators.required]],
    concepto: ['', Validators.required],
    monto: [0, [Validators.required, Validators.min(0.01)]],
    rnc: [''], ncf: [''], itbis: [0], emitirGastoMenor: [false],
  });

  protected guardar(): void {
    const v = this.form.getRawValue();
    this.ref.close(this.ingreso ? { concepto: v.concepto, monto: v.monto } : {
      beneficiario: v.beneficiario, concepto: v.concepto, monto: v.monto, emitirGastoMenor: v.emitirGastoMenor,
      rnc: v.emitirGastoMenor ? undefined : v.rnc || undefined, ncf: v.emitirGastoMenor ? undefined : v.ncf || undefined,
      itbis: v.emitirGastoMenor ? 0 : v.itbis || 0,
    });
  }
}

/** "RD$30.00" o, si es negativa (faltante), "(RD$10.00)", como el sistema anterior. */
export function valorDiferencia(d: number): string {
  const t = 'RD$' + Math.abs(d).toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 });
  return d < 0 ? `(${t})` : t;
}

/**
 * Cuadre y cierre de caja (como el sistema anterior): a la izquierda los totales del día por concepto y forma de
 * pago; a la derecha la distribución de lo contado (monedas por denominación más tarjetas, cheques y depósitos).
 * Diferencia = total contado − total del día: 0 caja cuadrada (verde); positiva, sobrante (amarillo); negativa,
 * faltante (rojo, entre paréntesis).
 */
@Component({
  selector: 'app-cierre-dialog',
  imports: [MatDialogModule, MatButtonModule, MatFormFieldModule, MatInputModule, FormsModule, CurrencyPipe],
  template: `
    <h2 mat-dialog-title>Cuadre de caja</h2>
    <mat-dialog-content>
      <div class="cuadre">
        <section>
          <h3>Totales de entradas y salidas del día</h3>
          <div class="tabla-contenedor"><table class="tabla">
            <tr><th>Descripción</th><th class="num">Efectivo</th><th class="num">Tarjeta</th><th class="num">Cheque</th><th class="num">Transf.</th><th class="num">Total</th></tr>
            @for (c of resumen()?.conceptos ?? []; track c.codigo) {
              <tr><td>{{ c.descripcion }}@if (c.codigo !== 'MONTO_INICIAL') { [{{ c.cantidad }}] }</td>
                <td class="num">{{ c.efectivo | currency }}</td><td class="num">{{ c.tarjeta | currency }}</td>
                <td class="num">{{ c.cheque | currency }}</td><td class="num">{{ c.transferencia | currency }}</td>
                <td class="num"><strong>{{ c.monto | currency }}</strong></td></tr>
            }
          </table></div>
          <div class="total-dia">Total del día <strong>{{ totalDia() | currency }}</strong></div>
          <div class="total-dia">Total contado <strong>{{ totalContado() | currency }}</strong></div>
          <div class="resultado" [class.cuadrada]="diferencia() === 0" [class.sobrante]="diferencia() > 0" [class.faltante]="diferencia() < 0">
            {{ resultado() }} : {{ valorDiferencia(diferencia()) }}
          </div>
          <mat-form-field style="width:100%"><mat-label>Observación</mat-label><input matInput [(ngModel)]="observacion" maxlength="200" /></mat-form-field>
        </section>
        <section>
          <h3>Distribución de las denominaciones</h3>
          <table class="conteo">
            <tr><th>Denominación</th><th>Cantidad</th><th class="num">Total</th></tr>
            @for (d of datos.denominaciones; track d; let i = $index) {
              <tr><td>{{ d | currency: 'DOP' : 'symbol' : '1.0-0' }}</td>
                <td><input type="number" min="0" class="celda" [ngModel]="cantidades()[i]" (ngModelChange)="fijar(i, +$event)" [attr.aria-label]="'Cantidad de ' + d" /></td>
                <td class="num">{{ d * cantidades()[i] | currency }}</td></tr>
            }
            <tr><td>Monto tarjetas</td><td colspan="2"><input type="number" min="0" class="celda ancha" [ngModel]="tarjetas()" (ngModelChange)="tarjetas.set(+$event || 0)" aria-label="Monto tarjetas" /></td></tr>
            <tr><td>Monto cheques</td><td colspan="2"><input type="number" min="0" class="celda ancha" [ngModel]="cheques()" (ngModelChange)="cheques.set(+$event || 0)" aria-label="Monto cheques" /></td></tr>
            <tr><td>Monto depósitos / transf.</td><td colspan="2"><input type="number" min="0" class="celda ancha" [ngModel]="transferencias()" (ngModelChange)="transferencias.set(+$event || 0)" aria-label="Monto depósitos" /></td></tr>
          </table>
          <div class="total-dia">Total monedas <strong>{{ totalContado() | currency }}</strong></div>
        </section>
      </div>
    </mat-dialog-content>
    <mat-dialog-actions align="end">
      <button mat-button mat-dialog-close>Cancelar</button>
      <button mat-flat-button (click)="cerrar()">Cerrar caja</button>
    </mat-dialog-actions>`,
  styles: `.cuadre { display: grid; grid-template-columns: minmax(0, 3fr) minmax(260px, 2fr); gap: 20px; align-items: start; }
    @media (max-width: 900px) { .cuadre { grid-template-columns: 1fr; } }
    h3 { margin: 0 0 8px; font: var(--mat-sys-title-small); }
    .tabla, .conteo { width: 100%; border-collapse: collapse; td, th { padding: 4px 6px; text-align: left; border-bottom: 1px solid var(--mat-sys-outline-variant); } }
    .celda { width: 80px; padding: 5px; text-align: right; } .celda.ancha { width: 140px; }
    .total-dia { display: flex; justify-content: space-between; margin-top: 10px; padding: 8px 12px; border-radius: 8px;
      background: var(--mat-sys-surface-container-high); font: var(--mat-sys-title-medium); }
    .resultado { margin: 16px 0 12px; padding: 10px 14px; border-radius: 8px; font: var(--mat-sys-title-medium); }
    .resultado { font-weight: 600; }
    .cuadrada { background: #2e9e3e; color: #fff; } .faltante { background: #d32f2f; color: #fff; } .sobrante { background: #f5e100; color: #000; }`,
})
export class CierreDialog {
  protected readonly datos = inject<{ denominaciones: number[]; sesionId: number }>(MAT_DIALOG_DATA);
  private readonly ref = inject(MatDialogRef<CierreDialog>);
  protected readonly resumen = signal<S['CierreCajaCierreResumen'] | null>(null);
  protected readonly cantidades = signal<number[]>(this.datos.denominaciones.map(() => 0));
  protected readonly tarjetas = signal(0);
  protected readonly cheques = signal(0);
  protected readonly transferencias = signal(0);
  protected readonly efectivo = computed(() => redondear(this.cantidades().reduce((s, c, i) => s + c * this.datos.denominaciones[i], 0)));
  protected readonly totalContado = computed(() => redondear(this.efectivo() + this.tarjetas() + this.cheques() + this.transferencias()));
  protected readonly totalDia = computed(() => this.resumen()?.totalDia?.monto ?? 0);
  /** Como el sistema anterior: lo contado menos lo que dice el sistema (positiva: sobrante; negativa: faltante). */
  protected readonly diferencia = computed(() => redondear(this.totalContado() - this.totalDia()));
  protected readonly resultado = computed(() => this.diferencia() === 0 ? 'Caja cuadrada'
    : this.diferencia() > 0 ? 'Caja descuadrada hay un sobrante' : 'Caja descuadrada hay un faltante');
  protected readonly valorDiferencia = valorDiferencia;
  protected observacion = '';

  constructor() {
    inject(HttpClient).get<S['CierreCajaCierreResumen']>(`/api/v1/caja/sesiones/${this.datos.sesionId}/cierre`)
      .subscribe((r) => this.resumen.set(r));
  }

  protected fijar(i: number, v: number): void {
    this.cantidades.set(this.cantidades().map((c, j) => (j === i ? Math.max(0, Math.floor(v || 0)) : c)));
  }

  protected cerrar(): void {
    const conteo: S['CajaConteoItem'][] = this.datos.denominaciones
      .map((denominacion, i) => ({ denominacion, cantidad: this.cantidades()[i] }));
    this.ref.close({ conteo, observacion: this.observacion || undefined, tarjetas: this.tarjetas(), cheques: this.cheques(),
      transferencias: this.transferencias() } satisfies S['CajaCerrarRequest']);
  }
}
