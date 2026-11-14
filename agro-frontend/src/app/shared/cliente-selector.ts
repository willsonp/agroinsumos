import { Component, ElementRef, effect, inject, input, model, viewChild } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { FormControl, ReactiveFormsModule } from '@angular/forms';
import { toSignal } from '@angular/core/rxjs-interop';
import { MatAutocompleteModule, MatAutocompleteTrigger } from '@angular/material/autocomplete';
import { MatButtonModule } from '@angular/material/button';
import { MatDialog } from '@angular/material/dialog';
import { MatTooltipModule } from '@angular/material/tooltip';
import { ClienteBusquedaDialog } from './cliente-busqueda-dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { catchError, debounceTime, distinctUntilChanged, map, of, switchMap } from 'rxjs';
import type { Pagina, S } from '../api/tipos';
import { params } from './http';

type Cliente = S['ClienteResponse'];

/**
 * Selector de cliente: autocompleta por nombre, RNC o teléfono; la lupa (o F2) abre la ventana de búsqueda con la lista.
 */
@Component({
  selector: 'app-cliente-selector',
  imports: [ReactiveFormsModule, MatAutocompleteModule, MatFormFieldModule, MatInputModule, MatButtonModule, MatTooltipModule],
  template: `
    <mat-form-field style="width:100%">
      <mat-label>{{ etiqueta() }}</mat-label>
      <span matTextPrefix class="material-symbols-outlined" style="margin-right:6px">person_search</span>
      <input #campo matInput [formControl]="texto" [matAutocomplete]="auto" placeholder="Nombre, RNC o teléfono" (keydown.f2)="buscar($event)" />
      <button mat-icon-button matSuffix type="button" (click)="buscar($event)" matTooltip="Buscar cliente (F2)" aria-label="Buscar cliente">
        <span class="material-symbols-outlined">search</span></button>
      <mat-autocomplete #auto [displayWith]="mostrar" (optionSelected)="cliente.set($event.option.value)">
        @for (c of opciones(); track c.id) {
          <mat-option [value]="c">
            {{ c.nombre }} @if (c.rncCedula) { <span class="muted">· {{ c.rncCedula }}</span> }
          </mat-option>
        }
      </mat-autocomplete>
    </mat-form-field>`,
})
export class ClienteSelector {
  private readonly http = inject(HttpClient);
  private readonly dialog = inject(MatDialog);
  private readonly trigger = viewChild.required(MatAutocompleteTrigger);
  private readonly campo = viewChild.required<ElementRef<HTMLInputElement>>('campo');
  readonly cliente = model<Cliente | null>(null);
  readonly etiqueta = input('Cliente');
  protected readonly texto = new FormControl<string | Cliente>('');

  protected readonly opciones = toSignal(this.texto.valueChanges.pipe(
    debounceTime(250),
    map((v) => (typeof v === 'string' ? v.trim() : '')),
    distinctUntilChanged(),
    // Solo sugiere al escribir: con el campo vacío no aparece una lista (para verla toda está la lupa)
    switchMap((q) => !q ? of([] as Cliente[]) : this.http.get<Pagina<Cliente>>('/api/v1/catalogos/clientes', { params: params({ q, size: 15, sort: 'nombre' }) })
      .pipe(map((p) => p.content.filter((c) => c.estado === 'A')), catchError(() => of([])))),
  ), { initialValue: [] as Cliente[] });

  constructor() {
    // Si el padre fija el cliente (p.ej. consumidor final por defecto), se muestra en el campo
    effect(() => {
      const c = this.cliente();
      if (c && this.texto.value !== c) this.texto.setValue(c, { emitEvent: false });
      if (!c && typeof this.texto.value !== 'string') this.texto.setValue('', { emitEvent: false });
    });
    this.texto.valueChanges.subscribe((v) => { if (v === '') this.cliente.set(null); });
  }

  /** Ventana de búsqueda (lupa o F2), con lo escrito como filtro inicial. */
  protected buscar(ev?: Event): void {
    ev?.preventDefault();
    ev?.stopPropagation();   // el clic de la lupa no llega al campo (lo enfocaría y reabriría las sugerencias)
    this.trigger().closePanel();
    const v = this.texto.value;
    this.dialog.open(ClienteBusquedaDialog, { data: { texto: typeof v === 'string' ? v.trim() : '' }, width: 'min(960px, 96vw)',
      maxWidth: '96vw', restoreFocus: false })
      .afterClosed().subscribe((c?: Cliente) => {
        if (c) this.cliente.set(c);
        setTimeout(() => { this.campo().nativeElement.focus(); this.trigger().closePanel(); });
      });
  }

  protected mostrar = (c: Cliente | string | null) => (typeof c === 'string' ? c : c?.nombre ?? '');
}
