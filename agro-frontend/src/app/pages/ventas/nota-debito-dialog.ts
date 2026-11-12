import { Component, inject } from '@angular/core';
import { CurrencyPipe } from '@angular/common';
import { NonNullableFormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import type { S } from '../../api/tipos';
import { ComprobanteService } from '../../core/comprobante.service';

/** Cargo adicional al cliente (mora, intereses, diferencia de precio): emite nota de débito (e-CF 33 / B03 según el modo). */
@Component({
  selector: 'app-nota-debito-dialog',
  imports: [MatDialogModule, MatButtonModule, MatFormFieldModule, MatInputModule, MatSelectModule, ReactiveFormsModule, CurrencyPipe],
  template: `
    <h2 mat-dialog-title>Nota de débito{{ comp.entreParentesis(33) }}</h2>
    <mat-dialog-content [formGroup]="form" class="grid-form" style="min-width:min(480px,90vw)">
      <p class="ancho-completo">Factura {{ f.numero }} ({{ f.encf ?? f.ncfLegacy }}) · {{ f.clienteNombre }} · total {{ f.total | currency }}</p>
      <mat-form-field class="ancho-completo"><mat-label>Concepto</mat-label><input matInput formControlName="concepto" maxlength="90" placeholder="Mora por atraso, intereses..." /></mat-form-field>
      <mat-form-field><mat-label>Monto (sin ITBIS)</mat-label><input matInput type="number" formControlName="monto" /></mat-form-field>
      <mat-form-field><mat-label>ITBIS</mat-label><mat-select formControlName="indicadorItbis">
        <mat-option [value]="4">Exento (mora, intereses)</mat-option><mat-option [value]="1">Gravado 18 %</mat-option></mat-select></mat-form-field>
    </mat-dialog-content>
    <mat-dialog-actions align="end"><button mat-button mat-dialog-close>Cancelar</button>
      <button mat-flat-button [disabled]="form.invalid" (click)="emitir()">Emitir nota de débito</button></mat-dialog-actions>`,
})
export class NotaDebitoDialog {
  protected readonly comp = inject(ComprobanteService);
  protected readonly f = inject<S['FacturaResponse']>(MAT_DIALOG_DATA);
  private readonly ref = inject(MatDialogRef<NotaDebitoDialog>);
  protected readonly form = inject(NonNullableFormBuilder).group({
    concepto: ['', Validators.required], monto: [0, [Validators.required, Validators.min(0.01)]], indicadorItbis: [4],
  });

  protected emitir(): void {
    this.ref.close({ facturaId: this.f.id!, ...this.form.getRawValue() } satisfies S['NotaDebitoRequest']);
  }
}
