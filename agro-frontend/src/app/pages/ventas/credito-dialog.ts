import { Component, computed, inject, signal } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import type { S } from '../../api/tipos';
import { CurrencyPipe, DatePipe } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatButtonModule } from '@angular/material/button';
import { MatButtonToggleModule } from '@angular/material/button-toggle';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';

export interface CreditoDatos { clienteId: number; cliente: string; diasSugeridos: number; total: number; }

const PLAZOS = [15, 30, 45, 60];

/** Venta a crédito: los días se eligen al facturar (por defecto, los del cliente o 15). Devuelve los días. */
@Component({
  selector: 'app-credito-dialog',
  imports: [CurrencyPipe, DatePipe, FormsModule, MatDialogModule, MatButtonModule, MatButtonToggleModule, MatFormFieldModule, MatInputModule],
  template: `
    <h2 mat-dialog-title>Venta a crédito</h2>
    <mat-dialog-content class="grid-form" style="min-width:min(420px,90vw)">
      <p class="ancho-completo">{{ datos.cliente }} · esta factura <strong>{{ datos.total | currency }}</strong></p>
      @if (credito(); as c) {
        @if ((c.limite ?? 0) > 0) {
          <div class="ancho-completo credito" [class.excede]="excede()">
            <span>Límite <strong>{{ c.limite | currency }}</strong></span>
            <span>Debe <strong>{{ c.deuda | currency }}</strong></span>
            <span>Disponible <strong>{{ c.disponible | currency }}</strong></span>
          </div>
          @if (excede()) { <p class="ancho-completo error-texto">La factura excede el crédito disponible en {{ datos.total - (c.disponible ?? 0) | currency }}.
            Cobre una parte de contado o registre un abono primero.</p> }
        } @else { <p class="ancho-completo muted">Sin límite de crédito configurado · debe {{ c.deuda | currency }}</p> }
      }
      <mat-button-toggle-group class="ancho-completo" [value]="dias()" (change)="dias.set($event.value)" [hideSingleSelectionIndicator]="true" aria-label="Plazo">
        @for (p of plazos; track p) { <mat-button-toggle [value]="p">{{ p }} días</mat-button-toggle> }
      </mat-button-toggle-group>
      <mat-form-field><mat-label>Días de crédito</mat-label>
        <input matInput type="number" min="1" max="365" [ngModel]="dias()" (ngModelChange)="dias.set(+$event)" (keydown.enter)="aceptar()" /></mat-form-field>
      <p>Vence el <strong>{{ vence() | date: 'dd/MM/yyyy' }}</strong></p>
    </mat-dialog-content>
    <mat-dialog-actions align="end">
      <button mat-button mat-dialog-close>Cancelar</button>
      <button mat-flat-button [disabled]="!valido() || excede() || !credito()" (click)="aceptar()">Facturar a crédito</button>
    </mat-dialog-actions>`,
  styles: `.credito { display: flex; gap: 16px; flex-wrap: wrap; padding: 10px 12px; border-radius: 8px; background: var(--mat-sys-surface-container-high); }
    .credito.excede { background: var(--mat-sys-error-container); color: var(--mat-sys-on-error-container); }`,
})
export class CreditoDialog {
  protected readonly datos = inject<CreditoDatos>(MAT_DIALOG_DATA);
  private readonly ref = inject(MatDialogRef<CreditoDialog, number>);
  protected readonly plazos = PLAZOS;
  protected readonly dias = signal(this.datos.diasSugeridos > 0 ? this.datos.diasSugeridos : 15);
  protected readonly valido = computed(() => Number.isInteger(this.dias()) && this.dias() >= 1 && this.dias() <= 365);
  /** Límite, deuda y disponible del cliente (la regla la aplica el servidor al facturar; aquí se avisa antes). */
  protected readonly credito = signal<S['ClienteCredito'] | null>(null);
  protected readonly excede = computed(() => {
    const c = this.credito();
    return !!c && (c.limite ?? 0) > 0 && this.datos.total > (c.disponible ?? 0) + 0.001;
  });

  constructor() {
    inject(HttpClient).get<S['ClienteCredito']>(`/api/v1/catalogos/clientes/${this.datos.clienteId}/credito`)
      .subscribe((c) => this.credito.set(c));
  }

  protected readonly vence = computed(() => { const d = new Date(); d.setDate(d.getDate() + (this.valido() ? this.dias() : 0)); return d; });

  protected aceptar(): void { if (this.valido()) this.ref.close(this.dias()); }
}
