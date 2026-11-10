import { Component, computed, inject, signal } from '@angular/core';
import { CurrencyPipe } from '@angular/common';
import { FormsModule, NonNullableFormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatButtonModule } from '@angular/material/button';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import type { S } from '../../api/tipos';
import { redondear } from '../../shared/http';

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
      @if (!ingreso) {
        <mat-checkbox class="ancho-completo" formControlName="emitirGastoMenor">
          Sin comprobante del proveedor: emitir e-CF 43 (gasto menor)
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

/** Cierre: conteo de billetes y monedas con diferencia contra el efectivo esperado. */
@Component({
  selector: 'app-cierre-dialog',
  imports: [MatDialogModule, MatButtonModule, MatFormFieldModule, MatInputModule, FormsModule, CurrencyPipe],
  template: `
    <h2 mat-dialog-title>Cierre de caja</h2>
    <mat-dialog-content style="min-width:min(480px,90vw)">
      <table class="conteo">
        <tr><th>Denominación</th><th>Cantidad</th><th class="num">Subtotal</th></tr>
        @for (d of datos.denominaciones; track d; let i = $index) {
          <tr><td>{{ d | currency: 'DOP' : 'symbol' : '1.0-0' }}</td>
            <td><input type="number" min="0" class="celda" [ngModel]="cantidades()[i]" (ngModelChange)="fijar(i, +$event)" [attr.aria-label]="'Cantidad de ' + d" /></td>
            <td class="num">{{ d * cantidades()[i] | currency }}</td></tr>
        }
      </table>
      <div class="resumen">
        <div>Esperado <strong>{{ datos.esperado | currency }}</strong></div>
        <div>Contado <strong>{{ contado() | currency }}</strong></div>
        <div [class.error-texto]="diferencia() !== 0">Diferencia <strong>{{ diferencia() | currency }}</strong></div>
      </div>
      <mat-form-field style="width:100%"><mat-label>Observación</mat-label><input matInput [(ngModel)]="observacion" maxlength="200" /></mat-form-field>
    </mat-dialog-content>
    <mat-dialog-actions align="end">
      <button mat-button mat-dialog-close>Cancelar</button>
      <button mat-flat-button (click)="cerrar()">Cerrar caja</button>
    </mat-dialog-actions>`,
  styles: `.conteo { width: 100%; td, th { padding: 4px 6px; text-align: left; } } .celda { width: 90px; padding: 6px; text-align: right; }
    .resumen { display: flex; gap: 20px; margin: 12px 0; flex-wrap: wrap; font: var(--mat-sys-title-small); }`,
})
export class CierreDialog {
  protected readonly datos = inject<{ denominaciones: number[]; esperado: number }>(MAT_DIALOG_DATA);
  private readonly ref = inject(MatDialogRef<CierreDialog>);
  protected readonly cantidades = signal<number[]>(this.datos.denominaciones.map(() => 0));
  protected readonly contado = computed(() => redondear(this.cantidades().reduce((s, c, i) => s + c * this.datos.denominaciones[i], 0)));
  protected readonly diferencia = computed(() => redondear(this.contado() - this.datos.esperado));
  protected observacion = '';

  protected fijar(i: number, v: number): void {
    this.cantidades.set(this.cantidades().map((c, j) => (j === i ? Math.max(0, Math.floor(v || 0)) : c)));
  }

  protected cerrar(): void {
    const conteo: S['CajaConteoItem'][] = this.datos.denominaciones
      .map((denominacion, i) => ({ denominacion, cantidad: this.cantidades()[i] }));
    this.ref.close({ conteo, observacion: this.observacion || undefined });
  }
}
