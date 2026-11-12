import { Component, computed, inject, signal } from '@angular/core';
import { CurrencyPipe, DatePipe } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatButtonModule } from '@angular/material/button';
import { MatButtonToggleModule } from '@angular/material/button-toggle';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';

export interface CreditoDatos { cliente: string; diasSugeridos: number; total: number; limiteCredito?: number; }

const PLAZOS = [15, 30, 45, 60];

/** Venta a crédito: los días se eligen al facturar (por defecto, los del cliente o 15). Devuelve los días. */
@Component({
  selector: 'app-credito-dialog',
  imports: [CurrencyPipe, DatePipe, FormsModule, MatDialogModule, MatButtonModule, MatButtonToggleModule, MatFormFieldModule, MatInputModule],
  template: `
    <h2 mat-dialog-title>Venta a crédito</h2>
    <mat-dialog-content class="grid-form" style="min-width:min(420px,90vw)">
      <p class="ancho-completo">{{ datos.cliente }} · total <strong>{{ datos.total | currency }}</strong>
        @if (datos.limiteCredito) { <br /><span class="muted">Límite de crédito {{ datos.limiteCredito | currency }}</span> }</p>
      <mat-button-toggle-group class="ancho-completo" [value]="dias()" (change)="dias.set($event.value)" [hideSingleSelectionIndicator]="true" aria-label="Plazo">
        @for (p of plazos; track p) { <mat-button-toggle [value]="p">{{ p }} días</mat-button-toggle> }
      </mat-button-toggle-group>
      <mat-form-field><mat-label>Días de crédito</mat-label>
        <input matInput type="number" min="1" max="365" [ngModel]="dias()" (ngModelChange)="dias.set(+$event)" (keydown.enter)="aceptar()" /></mat-form-field>
      <p>Vence el <strong>{{ vence() | date: 'dd/MM/yyyy' }}</strong></p>
    </mat-dialog-content>
    <mat-dialog-actions align="end">
      <button mat-button mat-dialog-close>Cancelar</button>
      <button mat-flat-button [disabled]="!valido()" (click)="aceptar()">Facturar a crédito</button>
    </mat-dialog-actions>`,
})
export class CreditoDialog {
  protected readonly datos = inject<CreditoDatos>(MAT_DIALOG_DATA);
  private readonly ref = inject(MatDialogRef<CreditoDialog, number>);
  protected readonly plazos = PLAZOS;
  protected readonly dias = signal(this.datos.diasSugeridos > 0 ? this.datos.diasSugeridos : 15);
  protected readonly valido = computed(() => Number.isInteger(this.dias()) && this.dias() >= 1 && this.dias() <= 365);
  protected readonly vence = computed(() => { const d = new Date(); d.setDate(d.getDate() + (this.valido() ? this.dias() : 0)); return d; });

  protected aceptar(): void { if (this.valido()) this.ref.close(this.dias()); }
}
