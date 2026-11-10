import { Component, computed, inject, signal } from '@angular/core';
import { CurrencyPipe } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { CuentasBancarias } from '../../shared/cuentas-bancarias';
import type { S } from '../../api/tipos';
import { redondear } from '../../shared/http';

/** Cobro en caja: varias formas de pago a la vez; la devuelta solo sale del efectivo. */
@Component({
  selector: 'app-pago-dialog',
  imports: [MatDialogModule, MatButtonModule, MatFormFieldModule, MatInputModule, MatSelectModule, FormsModule, CurrencyPipe],
  template: `
    <h2 mat-dialog-title>Cobrar {{ total | currency }}</h2>
    <mat-dialog-content class="contenido">
      <div class="rapidos">
        <button mat-stroked-button (click)="efectivo.set(total)">Exacto</button>
        @for (b of billetes; track b) { <button mat-stroked-button (click)="efectivo.set(b)">{{ b | currency: 'DOP' : 'symbol' : '1.0-0' }}</button> }
      </div>
      <div class="grid-form">
        <mat-form-field><mat-label>Efectivo recibido</mat-label>
          <input matInput type="number" min="0" [ngModel]="efectivo()" (ngModelChange)="efectivo.set(+$event || 0)" cdkFocusInitial /></mat-form-field>
        <mat-form-field><mat-label>Tarjeta</mat-label>
          <input matInput type="number" min="0" [ngModel]="tarjeta()" (ngModelChange)="tarjeta.set(+$event || 0)" /></mat-form-field>
        <mat-form-field><mat-label>No. autorización</mat-label><input matInput [(ngModel)]="autorizacion" /></mat-form-field>
        <mat-form-field><mat-label>Transferencia</mat-label>
          <input matInput type="number" min="0" [ngModel]="transferencia()" (ngModelChange)="transferencia.set(+$event || 0)" /></mat-form-field>
        <mat-form-field><mat-label>Referencia transferencia</mat-label><input matInput [(ngModel)]="refTransferencia" /></mat-form-field>
        @if (transferencia() > 0 && cuentas.activas().length) {
          <mat-form-field class="ancho-completo"><mat-label>Transferencia recibida en</mat-label><mat-select [(ngModel)]="cuentaTransferencia">
            <mat-option [value]="null">Sin vincular al módulo Banco</mat-option>
            @for (c of cuentas.activas(); track c.id) { <mat-option [value]="c.id">{{ etiqueta(c) }}</mat-option> }
          </mat-select></mat-form-field>
        }
        <mat-form-field><mat-label>Cheque</mat-label>
          <input matInput type="number" min="0" [ngModel]="cheque()" (ngModelChange)="cheque.set(+$event || 0)" /></mat-form-field>
        @if (disponibleAFavor > 0) {
          <mat-form-field><mat-label>Saldo a favor (disp. {{ disponibleAFavor | currency }})</mat-label>
            <input matInput type="number" min="0" [max]="disponibleAFavor" [ngModel]="saldoAFavor()" (ngModelChange)="saldoAFavor.set(+$event || 0)" /></mat-form-field>
        }
      </div>
      <div class="resumen">
        <div>Recibido <strong>{{ recibido() | currency }}</strong></div>
        @if (falta() > 0) { <div class="error-texto">Falta <strong>{{ falta() | currency }}</strong></div> }
        @else { <div class="devuelta">Devuelta <strong>{{ devuelta() | currency }}</strong></div> }
        @if (excesoNoEfectivo()) { <div class="error-texto">Tarjeta, transferencia, cheque y saldo a favor no pueden exceder el total</div> }
      </div>
    </mat-dialog-content>
    <mat-dialog-actions align="end">
      <button mat-button mat-dialog-close>Cancelar</button>
      <button mat-flat-button [disabled]="!valido()" (click)="confirmar()">Confirmar cobro</button>
    </mat-dialog-actions>`,
  styles: `
    .contenido { min-width: min(520px, 90vw); }
    .rapidos { display: flex; gap: 8px; flex-wrap: wrap; margin-bottom: 12px; }
    .resumen { display: flex; gap: 24px; font: var(--mat-sys-title-medium); flex-wrap: wrap; }
    .devuelta strong { color: var(--mat-sys-primary); font-size: 1.4em; }`,
})
export class PagoDialog {
  private readonly datos = inject<{ total: number; saldoAFavor?: number }>(MAT_DIALOG_DATA);
  protected readonly total = this.datos.total;
  protected readonly disponibleAFavor = this.datos.saldoAFavor ?? 0;
  protected readonly saldoAFavor = signal(0);
  private readonly ref = inject(MatDialogRef<PagoDialog>);
  protected readonly billetes = [100, 200, 500, 1000, 2000];
  protected readonly efectivo = signal(0);
  protected readonly tarjeta = signal(0);
  protected readonly transferencia = signal(0);
  protected readonly cheque = signal(0);
  protected autorizacion = '';
  protected refTransferencia = '';
  protected readonly cuentas = inject(CuentasBancarias);
  protected readonly etiqueta = CuentasBancarias.etiqueta;
  protected cuentaTransferencia: number | null = null;

  constructor() {
    this.cuentas.cargar();
  }

  private readonly noEfectivo = computed(() => this.tarjeta() + this.transferencia() + this.cheque() + this.saldoAFavor());
  protected readonly recibido = computed(() => redondear(this.efectivo() + this.noEfectivo()));
  protected readonly falta = computed(() => redondear(Math.max(0, this.total - this.recibido())));
  protected readonly devuelta = computed(() => redondear(Math.max(0, this.recibido() - this.total)));
  protected readonly excesoNoEfectivo = computed(() => this.noEfectivo() > this.total + 0.001);
  protected readonly valido = computed(() => this.falta() === 0 && !this.excesoNoEfectivo() && this.recibido() > 0
    && this.saldoAFavor() <= this.disponibleAFavor);

  protected confirmar(): void {
    const pagos: S['PagoRequest'][] = [];
    if (this.efectivo() > 0) pagos.push({ forma: 'EFECTIVO', monto: this.efectivo() });
    if (this.tarjeta() > 0) pagos.push({ forma: 'TARJETA', monto: this.tarjeta(), referencia: this.autorizacion || undefined });
    if (this.transferencia() > 0) pagos.push({ forma: 'TRANSFERENCIA', monto: this.transferencia(), referencia: this.refTransferencia || undefined,
      cuentaBancariaId: this.cuentaTransferencia ?? undefined });
    if (this.cheque() > 0) pagos.push({ forma: 'CHEQUE', monto: this.cheque() });
    if (this.saldoAFavor() > 0) pagos.push({ forma: 'SALDO_A_FAVOR', monto: this.saldoAFavor() });
    this.ref.close(pagos);
  }
}
