import { Component, ElementRef, inject, signal, viewChildren } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { FormControl, ReactiveFormsModule } from '@angular/forms';
import { toSignal } from '@angular/core/rxjs-interop';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { catchError, debounceTime, distinctUntilChanged, map, of, startWith, switchMap, tap } from 'rxjs';
import type { Pagina, S } from '../api/tipos';
import { params } from './http';

type Proveedor = S['ProveedorResponse'];

/** Ventana de búsqueda de suplidores (como la de artículos y clientes): filtro por nombre o RNC; clic o Enter elige. */
@Component({
  selector: 'app-proveedor-busqueda-dialog',
  imports: [ReactiveFormsModule, MatDialogModule, MatFormFieldModule, MatInputModule, MatProgressBarModule],
  template: `
    <h2 mat-dialog-title>Buscar suplidor</h2>
    <mat-dialog-content>
      <mat-form-field class="filtro">
        <mat-label>Escriba parte del nombre o el RNC/cédula</mat-label>
        <span matTextPrefix class="material-symbols-outlined" style="margin-right:6px">search</span>
        <input matInput [formControl]="texto" (keydown)="teclado($event)" cdkFocusInitial autocomplete="off" />
      </mat-form-field>
      @if (cargando()) { <mat-progress-bar mode="indeterminate" /> }
      <div class="lista" role="listbox" aria-label="Suplidores">
        <table>
          <thead><tr><th>Código</th><th>Nombre</th><th>RNC / Cédula</th><th>Teléfono</th><th>Contacto</th></tr></thead>
          <tbody>
            @for (p of proveedores(); track p.id; let i = $index) {
              <tr #fila role="option" [attr.aria-selected]="i === activo()" [class.activo]="i === activo()" (click)="elegir(p)" (mouseenter)="activo.set(i)">
                <td>{{ p.id }}</td><td>{{ p.nombre }}</td><td>{{ p.rncCedula }}</td><td>{{ p.telefono }}</td><td>{{ p.contacto }}</td>
              </tr>
            } @empty {
              @if (!cargando()) { <tr><td colspan="5" class="muted vacio">No hay suplidores que coincidan con «{{ texto.value }}»</td></tr> }
            }
          </tbody>
        </table>
      </div>
      <p class="muted pequeno">{{ proveedores().length }} suplidor(es){{ proveedores().length >= limite ? ' (se muestran los primeros ' + limite + '; afine la búsqueda)' : '' }}
        · ↑ ↓ para moverse, Enter o clic para elegir, Esc para cerrar</p>
    </mat-dialog-content>`,
  styles: `.filtro { width: 100%; }
    .lista { height: min(440px, 55vh); overflow: auto; border: 1px solid var(--mat-sys-outline-variant); border-radius: 8px; }
    table { width: 100%; border-collapse: collapse; }
    th { position: sticky; top: 0; background: var(--mat-sys-surface-container-high); text-align: left; padding: 8px; font: var(--mat-sys-label-large); }
    td { padding: 7px 8px; border-bottom: 1px solid var(--mat-sys-outline-variant); }
    tbody tr { cursor: pointer; }
    tr.activo td { background: var(--mat-sys-secondary-container); color: var(--mat-sys-on-secondary-container); }
    .vacio { padding: 24px; text-align: center; cursor: default; }`,
})
export class ProveedorBusquedaDialog {
  private readonly http = inject(HttpClient);
  private readonly ref = inject(MatDialogRef<ProveedorBusquedaDialog, Proveedor>);
  private readonly filas = viewChildren<ElementRef<HTMLTableRowElement>>('fila');
  protected readonly limite = 100;
  protected readonly texto = new FormControl(inject<{ texto?: string } | null>(MAT_DIALOG_DATA)?.texto ?? '', { nonNullable: true });
  protected readonly activo = signal(0);
  protected readonly cargando = signal(true);

  protected readonly proveedores = toSignal(this.texto.valueChanges.pipe(
    startWith(this.texto.value),
    map((v) => v.trim()),
    debounceTime(200),
    distinctUntilChanged(),
    tap(() => this.cargando.set(true)),
    switchMap((q) => this.http.get<Pagina<Proveedor>>('/api/v1/catalogos/proveedores', { params: params({ q, size: this.limite, sort: 'nombre' }) })
      .pipe(map((p) => p.content.filter((x) => x.estado === 'A')), catchError(() => of([] as Proveedor[])))),
    tap(() => { this.cargando.set(false); this.activo.set(0); }),
  ), { initialValue: [] as Proveedor[] });

  protected teclado(ev: KeyboardEvent): void {
    const n = this.proveedores().length;
    const mover = (i: number) => { this.activo.set(i); this.filas()[i]?.nativeElement.scrollIntoView({ block: 'nearest' }); };
    if (ev.key === 'ArrowDown' || ev.key === 'ArrowUp') {
      ev.preventDefault();
      if (n) mover((this.activo() + (ev.key === 'ArrowDown' ? 1 : n - 1)) % n);
    } else if (ev.key === 'PageDown' || ev.key === 'PageUp') {
      ev.preventDefault();
      if (n) mover(Math.max(0, Math.min(n - 1, this.activo() + (ev.key === 'PageDown' ? 10 : -10))));
    } else if (ev.key === 'Enter') {
      ev.preventDefault();
      const p = this.proveedores()[this.activo()];
      if (p) this.elegir(p);
    }
  }

  protected elegir(p: Proveedor): void { this.ref.close(p); }
}
