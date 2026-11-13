import { Component, inject, signal } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { CurrencyPipe, DatePipe } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatDatepickerModule } from '@angular/material/datepicker';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatTabsModule } from '@angular/material/tabs';
import { MatDialog } from '@angular/material/dialog';
import { MatTooltipModule } from '@angular/material/tooltip';
import { ReciboDialog } from './recibo-dialog';
import type { S } from '../../api/tipos';
import { AuthService } from '../../core/auth.service';
import { Notificacion } from '../../core/notificacion.service';
import { ClienteSelector } from '../../shared/cliente-selector';
import { isoFecha, params } from '../../shared/http';

const TIPOS: Record<string, string> = { FACTURA: 'Factura', NOTA_DEBITO: 'Nota de débito', RECIBO: 'Recibo', NOTA_CREDITO: 'Nota de crédito' };

@Component({
  selector: 'app-estado-cuenta',
  imports: [CurrencyPipe, DatePipe, FormsModule, MatButtonModule, MatDatepickerModule, MatFormFieldModule, MatInputModule, MatTabsModule, MatTooltipModule, ClienteSelector],
  template: `
    <div class="pagina">
      <div class="pagina-titulo"><h1>Cuentas por cobrar</h1><button mat-stroked-button class="no-imprimir" (click)="imprimir()">Imprimir</button></div>
      <mat-tab-group>
        <mat-tab label="Estado de cuenta">
          <div class="tarjeta">
            <div class="fila no-imprimir">
              <div style="flex:1 1 320px"><app-cliente-selector [(cliente)]="cliente" /></div>
              <mat-form-field><mat-label>Desde</mat-label><input matInput [matDatepicker]="d1" [(ngModel)]="desde" />
                <mat-datepicker-toggle matIconSuffix [for]="d1" /><mat-datepicker #d1 /></mat-form-field>
              <mat-form-field><mat-label>Hasta</mat-label><input matInput [matDatepicker]="d2" [(ngModel)]="hasta" />
                <mat-datepicker-toggle matIconSuffix [for]="d2" /><mat-datepicker #d2 /></mat-form-field>
              <button mat-flat-button [disabled]="!cliente()" (click)="consultar()">Consultar</button>
            </div>
            @if (estado(); as e) {
              <h2>{{ e.cliente }} <span class="muted">· {{ e.desde | date: 'dd/MM/yyyy' }} al {{ e.hasta | date: 'dd/MM/yyyy' }}</span></h2>
              <table class="tabla">
                <tr><th>Fecha</th><th>Documento</th><th>Referencia</th><th class="num">Débito</th><th class="num">Crédito</th><th class="num">Balance</th></tr>
                <tr><td colspan="5"><em>Saldo inicial</em></td><td class="num">{{ e.saldoInicial | currency }}</td></tr>
                @for (m of e.movimientos; track $index) {
                  <tr><td>{{ m.fecha | date: 'dd/MM/yyyy' }}</td><td>{{ tipo(m.tipo) }} {{ m.numero }}
                      @if (m.tipo === 'RECIBO') { <button mat-icon-button class="no-imprimir" (click)="reimprimir(m.documentoId!)" matTooltip="Reimprimir recibo" aria-label="Reimprimir recibo"><span class="material-symbols-outlined">print</span></button> }</td><td>{{ m.referencia }}</td>
                    <td class="num">{{ m.debito ? (m.debito | currency) : '' }}</td><td class="num">{{ m.credito ? (m.credito | currency) : '' }}</td>
                    <td class="num">{{ m.balance | currency }}</td></tr>
                }
                <tr class="final"><td colspan="5">Saldo final</td><td class="num">{{ e.saldoFinal | currency }}</td></tr>
              </table>
              @if ((e.saldoAFavor ?? 0) > 0) { <p>Saldo a favor del cliente: <strong>{{ e.saldoAFavor | currency }}</strong></p> }
            }
          </div>
        </mat-tab>
        @if (auth.esSupervisor()) {
          <mat-tab label="Antigüedad de saldos">
            <div class="tarjeta tabla-contenedor">
              <button mat-stroked-button class="no-imprimir" (click)="antiguedadCargar()">Actualizar</button>
              <table class="tabla">
                <tr><th>Cliente</th><th class="num">Corriente</th><th class="num">1-30</th><th class="num">31-60</th><th class="num">61-90</th><th class="num">+90</th><th class="num">Total</th></tr>
                @for (a of antiguedad(); track a.clienteId) {
                  <tr><td>{{ a.cliente }}</td><td class="num">{{ a.corriente | currency }}</td><td class="num">{{ a.d1a30 | currency }}</td>
                    <td class="num">{{ a.d31a60 | currency }}</td><td class="num">{{ a.d61a90 | currency }}</td>
                    <td class="num vencido">{{ a.mas90 | currency }}</td><td class="num"><strong>{{ a.total | currency }}</strong></td></tr>
                } @empty { <tr><td colspan="7" class="muted">Sin saldos pendientes</td></tr> }
              </table>
            </div>
          </mat-tab>
        }
      </mat-tab-group>
    </div>`,
  styles: `.tabla { width: 100%; border-collapse: collapse; margin-top: 12px; td, th { padding: 6px 8px; border-bottom: 1px solid var(--mat-sys-outline-variant); text-align: left; } }
    .final td { font-weight: 700; } .vencido { color: var(--mat-sys-error); }`,
})
export class EstadoCuenta {
  private readonly http = inject(HttpClient);
  private readonly dialog = inject(MatDialog);

  protected reimprimir(reciboId: number): void { this.dialog.open(ReciboDialog, { data: { reciboId } }); }
  private readonly notif = inject(Notificacion);
  protected readonly auth = inject(AuthService);
  protected readonly cliente = signal<S['ClienteResponse'] | null>(null);
  protected readonly estado = signal<S['EstadoCuenta'] | null>(null);
  protected readonly antiguedad = signal<S['EstadoCuentaAntiguedad'][]>([]);
  protected desde: Date | null = new Date(new Date().getFullYear(), 0, 1);
  protected hasta: Date | null = new Date();

  constructor() { if (this.auth.esSupervisor()) this.antiguedadCargar(); }

  protected consultar(): void {
    this.http.get<S['EstadoCuenta']>(`/api/v1/cxc/clientes/${this.cliente()!.id}/estado-cuenta`,
      { params: params({ desde: isoFecha(this.desde), hasta: isoFecha(this.hasta) }) })
      .subscribe({ next: (e) => this.estado.set(e), error: (e) => this.notif.error(e) });
  }

  protected antiguedadCargar(): void {
    this.http.get<S['EstadoCuentaAntiguedad'][]>('/api/v1/cxc/antiguedad').subscribe((a) => this.antiguedad.set(a));
  }

  protected tipo(t?: string): string { return TIPOS[t ?? ''] ?? t ?? ''; }

  protected imprimir(): void { window.print(); }
}
