import { Component, inject, signal } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { CurrencyPipe, DatePipe } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { MatTableModule } from '@angular/material/table';
import { MatPaginatorModule, PageEvent } from '@angular/material/paginator';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatSelectModule } from '@angular/material/select';
import { MatButtonModule } from '@angular/material/button';
import { MatTooltipModule } from '@angular/material/tooltip';
import type { Pagina, S } from '../../api/tipos';
import { EstadoEcf } from '../../shared/estado-ecf';
import { descargar, params } from '../../shared/http';
import { Notificacion } from '../../core/notificacion.service';

/** Comprobantes fiscales electrónicos: estado ante la DGII, envío manual, consulta y XML firmado. */
@Component({
  selector: 'app-ecf-documentos',
  imports: [CurrencyPipe, DatePipe, FormsModule, MatTableModule, MatPaginatorModule, MatFormFieldModule, MatSelectModule,
    MatButtonModule, MatTooltipModule, EstadoEcf],
  template: `
    <div class="pagina">
      <div class="pagina-titulo"><h1>Comprobantes electrónicos (e-CF)</h1>
        <mat-form-field style="width:260px"><mat-label>Estado</mat-label>
          <mat-select [(ngModel)]="estado" (selectionChange)="cargar()">
            <mat-option [value]="null">Todos</mat-option>
            @for (e of estados; track e) { <mat-option [value]="e">{{ e }}</mat-option> }
          </mat-select></mat-form-field>
      </div>
      <div class="tarjeta tabla-contenedor">
        <table mat-table [dataSource]="pagina()?.content ?? []">
          <ng-container matColumnDef="encf"><th mat-header-cell *matHeaderCellDef>e-NCF</th><td mat-cell *matCellDef="let d">{{ d.encf }}</td></ng-container>
          <ng-container matColumnDef="fecha"><th mat-header-cell *matHeaderCellDef>Fecha</th><td mat-cell *matCellDef="let d">{{ d.fechaEmision | date: 'dd/MM/yyyy' }}</td></ng-container>
          <ng-container matColumnDef="comprador"><th mat-header-cell *matHeaderCellDef>Comprador / Vendedor</th>
            <td mat-cell *matCellDef="let d">{{ d.razonSocialComprador ?? 'Consumidor final' }} <span class="muted">{{ d.rncComprador }}</span></td></ng-container>
          <ng-container matColumnDef="origen"><th mat-header-cell *matHeaderCellDef>Origen</th><td mat-cell *matCellDef="let d">{{ d.origenTipo }} {{ d.origenId }}</td></ng-container>
          <ng-container matColumnDef="total"><th mat-header-cell *matHeaderCellDef class="num">Total</th><td mat-cell *matCellDef="let d" class="num">{{ d.montoTotal | currency }}</td></ng-container>
          <ng-container matColumnDef="estado"><th mat-header-cell *matHeaderCellDef>Estado DGII</th>
            <td mat-cell *matCellDef="let d"><app-estado-ecf [estado]="d.estado" />
              @if (d.resumenConsumo) { <span class="chip" matTooltip="Consumo menor de RD$250,000: se envía el resumen RFCE">RFCE</span> }
              @if (d.ultimoError) { <div class="error-texto pequeno">{{ d.ultimoError }}</div> }</td></ng-container>
          <ng-container matColumnDef="acciones"><th mat-header-cell *matHeaderCellDef></th>
            <td mat-cell *matCellDef="let d" style="white-space:nowrap">
              @if (d.estado === 'FIRMADO' || d.estado === 'ERROR_ENVIO') { <button mat-button (click)="enviar(d)">Enviar</button> }
              @if (d.estado === 'ENVIADO' || d.estado === 'EN_PROCESO') { <button mat-button (click)="consultar(d)">Consultar</button> }
              <button mat-button (click)="xml(d)">XML</button>
            </td></ng-container>
          <tr mat-header-row *matHeaderRowDef="columnas"></tr>
          <tr mat-row *matRowDef="let r; columns: columnas"></tr>
        </table>
        <mat-paginator [length]="pagina()?.totalElements ?? 0" [pageSize]="20" (page)="cargar($event)" />
      </div>
    </div>`,
  styles: `.pequeno { font-size: 12px; max-width: 320px; }`,
})
export class EcfDocumentos {
  private readonly http = inject(HttpClient);
  private readonly notif = inject(Notificacion);
  protected readonly estados = ['FIRMADO', 'ENVIADO', 'EN_PROCESO', 'ACEPTADO', 'ACEPTADO_CONDICIONAL', 'RECHAZADO', 'ERROR_ENVIO'];
  protected readonly columnas = ['encf', 'fecha', 'comprador', 'origen', 'total', 'estado', 'acciones'];
  protected readonly pagina = signal<Pagina<S['EcfResponse']> | null>(null);
  protected estado: string | null = null;

  constructor() { this.cargar(); }

  protected cargar(e?: PageEvent): void {
    this.http.get<Pagina<S['EcfResponse']>>('/api/v1/ecf/documentos', { params: params({ estado: this.estado, page: e?.pageIndex ?? 0, size: 20 }) })
      .subscribe({ next: (p) => this.pagina.set(p), error: (err) => this.notif.error(err) });
  }

  protected enviar(d: S['EcfResponse']): void {
    this.http.post<S['EcfResponse']>(`/api/v1/ecf/documentos/${d.id}/enviar`, {}).subscribe({
      next: (r) => { this.notif.ok(`${r.encf}: ${r.estado}`); this.cargar(); }, error: (e) => this.notif.error(e) });
  }

  protected consultar(d: S['EcfResponse']): void {
    this.http.post<S['EcfResponse']>(`/api/v1/ecf/documentos/${d.id}/consultar-estado`, {}).subscribe({
      next: (r) => { this.notif.ok(`${r.encf}: ${r.estado}`); this.cargar(); }, error: (e) => this.notif.error(e) });
  }

  protected xml(d: S['EcfResponse']): void {
    this.http.get(`/api/v1/ecf/documentos/${d.id}/xml`, { responseType: 'blob' }).subscribe((b) => descargar(b, `${d.encf}.xml`));
  }
}
