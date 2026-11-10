import { Component, effect, inject, input, model } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { FormControl, ReactiveFormsModule } from '@angular/forms';
import { toSignal } from '@angular/core/rxjs-interop';
import { MatAutocompleteModule } from '@angular/material/autocomplete';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { catchError, debounceTime, distinctUntilChanged, map, of, switchMap } from 'rxjs';
import type { Pagina, S } from '../api/tipos';
import { params } from './http';

type Cliente = S['ClienteResponse'];

@Component({
  selector: 'app-cliente-selector',
  imports: [ReactiveFormsModule, MatAutocompleteModule, MatFormFieldModule, MatInputModule],
  template: `
    <mat-form-field style="width:100%">
      <mat-label>{{ etiqueta() }}</mat-label>
      <span matTextPrefix class="material-symbols-outlined" style="margin-right:6px">person_search</span>
      <input matInput [formControl]="texto" [matAutocomplete]="auto" placeholder="Nombre, RNC o teléfono" />
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
  readonly cliente = model<Cliente | null>(null);
  readonly etiqueta = input('Cliente');
  protected readonly texto = new FormControl<string | Cliente>('');

  protected readonly opciones = toSignal(this.texto.valueChanges.pipe(
    debounceTime(250),
    map((v) => (typeof v === 'string' ? v : '')),
    distinctUntilChanged(),
    switchMap((q) => this.http.get<Pagina<Cliente>>('/api/v1/catalogos/clientes', { params: params({ q, size: 15, sort: 'nombre' }) })
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

  protected mostrar = (c: Cliente | string | null) => (typeof c === 'string' ? c : c?.nombre ?? '');
}
