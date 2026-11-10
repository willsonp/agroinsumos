import { Component, computed, inject, signal } from '@angular/core';
import { CurrencyPipe, DecimalPipe } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatButtonModule } from '@angular/material/button';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import type { S } from '../../api/tipos';

type Linea = S['FacturaLineaResponse'];

/** Devolución parcial: cantidades por línea (máximo lo pendiente) y si reingresa al inventario. */
@Component({
  selector: 'app-devolucion-dialog',
  imports: [MatDialogModule, MatButtonModule, MatCheckboxModule, MatFormFieldModule, MatInputModule, FormsModule, CurrencyPipe, DecimalPipe],
  template: `
    <h2 mat-dialog-title>Devolución · Nota de crédito (e-CF 34)</h2>
    <mat-dialog-content>
      <table class="tabla">
        <tr><th>Artículo</th><th class="num">Pendiente</th><th class="num">Devolver</th><th>Reingresa</th></tr>
        @for (l of lineas; track l.id; let i = $index) {
          <tr>
            <td>{{ l.descripcion }}<div class="muted">{{ l.precioUnitario | currency }} c/u</div></td>
            <td class="num">{{ pendiente(l) | number: '1.0-3' }}</td>
            <td class="num"><input type="number" min="0" [max]="pendiente(l)" class="celda" [ngModel]="cantidades()[i]"
                                   (ngModelChange)="fijar(i, +$event)" /></td>
            <td><mat-checkbox [(ngModel)]="reingresa[i]" /></td>
          </tr>
        }
      </table>
      <mat-form-field style="width:100%;margin-top:12px"><mat-label>Motivo</mat-label>
        <input matInput [(ngModel)]="motivo" maxlength="90" /></mat-form-field>
      @if (excede()) { <p class="error-texto">Hay cantidades mayores a lo pendiente por devolver</p> }
    </mat-dialog-content>
    <mat-dialog-actions align="end">
      <button mat-button mat-dialog-close>Cancelar</button>
      <button mat-flat-button [disabled]="!valido()" (click)="confirmar()">Emitir nota de crédito</button>
    </mat-dialog-actions>`,
  styles: `.tabla { width: 100%; border-collapse: collapse; td, th { padding: 6px; border-bottom: 1px solid var(--mat-sys-outline-variant); text-align: left; } }
    .celda { width: 80px; padding: 6px; text-align: right; }`,
})
export class DevolucionDialog {
  private readonly factura = inject<S['FacturaResponse']>(MAT_DIALOG_DATA);
  private readonly ref = inject(MatDialogRef<DevolucionDialog>);
  protected readonly lineas: Linea[] = (this.factura.lineas ?? []).filter((l) => this.pendiente(l) > 0);
  protected readonly cantidades = signal<number[]>(this.lineas.map(() => 0));
  protected readonly reingresa = this.lineas.map(() => true);
  protected motivo = '';
  protected readonly excede = computed(() => this.cantidades().some((c, i) => c > this.pendiente(this.lineas[i])));
  protected readonly valido = computed(() => !this.excede() && this.cantidades().some((c) => c > 0));

  protected pendiente(l: Linea): number { return (l.cantidad ?? 0) - (l.cantidadDevuelta ?? 0); }

  protected fijar(i: number, v: number): void { this.cantidades.set(this.cantidades().map((c, j) => (j === i ? Math.max(0, v || 0) : c))); }

  protected confirmar(): void {
    if (!this.motivo.trim()) { this.motivo = 'Devolución de mercancía'; }
    const req: S['DevolucionRequest'] = {
      facturaId: this.factura.id!, motivo: this.motivo.trim(),
      lineas: this.lineas.map((l, i) => ({ facturaLineaId: l.id!, cantidad: this.cantidades()[i], reingresaInventario: this.reingresa[i] }))
        .filter((l) => l.cantidad > 0),
    };
    this.ref.close(req);
  }
}
