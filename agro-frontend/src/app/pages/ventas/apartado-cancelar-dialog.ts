import { Component, inject } from '@angular/core';
import { CurrencyPipe } from '@angular/common';
import { FormControl, ReactiveFormsModule, Validators } from '@angular/forms';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';

export interface CancelarApartadoResultado { motivo: string; devolverDinero: boolean; }

/**
 * Cancelación de un apartado con las tres opciones explícitas: no hacer nada, cancelar devolviendo el dinero
 * (sale de la caja) o cancelar dejando lo abonado como saldo a favor del cliente (lo usa en otra compra).
 */
@Component({
  selector: 'app-apartado-cancelar-dialog',
  imports: [MatDialogModule, MatButtonModule, MatFormFieldModule, MatInputModule, ReactiveFormsModule, CurrencyPipe],
  template: `
    <h2 mat-dialog-title>Cancelar apartado {{ d.numero }}</h2>
    <mat-dialog-content>
      <p>Cliente: <strong>{{ d.cliente }}</strong> · Abonado: <strong>{{ d.abonado | currency }}</strong></p>
      <mat-form-field style="width:100%">
        <mat-label>Motivo de la cancelación</mat-label>
        <input matInput [formControl]="motivo" maxlength="150" cdkFocusInitial />
      </mat-form-field>
      @if (d.abonado > 0) {
        <p class="muted">¿Qué se hace con lo abonado?</p>
        <ul class="opciones">
          <li><strong>Devolver el dinero:</strong> se le entregan {{ d.abonado | currency }} en efectivo desde su caja.</li>
          <li><strong>Dejar saldo a favor:</strong> el dinero queda a favor del cliente para usarlo en otra compra.</li>
          <li><strong>No cancelar:</strong> cierra esta ventana; el apartado sigue igual.</li>
        </ul>
      } @else {
        <p class="muted">El apartado no tiene abonos: solo se libera la mercancía reservada.</p>
      }
    </mat-dialog-content>
    <mat-dialog-actions align="end">
      <button mat-button mat-dialog-close>No cancelar</button>
      @if (d.abonado > 0) {
        <button mat-stroked-button [disabled]="motivo.invalid" (click)="cerrar(false)">
          <span class="material-symbols-outlined">savings</span> Cancelar y dejar saldo a favor</button>
        <button mat-flat-button class="peligro" [disabled]="motivo.invalid" (click)="cerrar(true)">
          <span class="material-symbols-outlined">payments</span> Cancelar y devolver dinero</button>
      } @else {
        <button mat-flat-button class="peligro" [disabled]="motivo.invalid" (click)="cerrar(false)">Cancelar apartado</button>
      }
    </mat-dialog-actions>`,
  styles: `.peligro { --mat-button-filled-container-color: var(--mat-sys-error); }
    .opciones { margin: 4px 0 0; padding-left: 20px; li { margin-bottom: 4px; } }`,
})
export class ApartadoCancelarDialog {
  protected readonly d = inject<{ numero: number; cliente: string; abonado: number }>(MAT_DIALOG_DATA);
  private readonly ref = inject(MatDialogRef<ApartadoCancelarDialog, CancelarApartadoResultado>);
  protected readonly motivo = new FormControl('', { nonNullable: true, validators: [Validators.required, Validators.pattern(/\S/)] });

  protected cerrar(devolverDinero: boolean): void { this.ref.close({ motivo: this.motivo.value.trim(), devolverDinero }); }
}
