import { Component, inject } from '@angular/core';
import { FormControl, ReactiveFormsModule, Validators } from '@angular/forms';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';

export interface ConfirmarDatos {
  titulo: string;
  mensaje: string;
  accion?: string;
  /** Si se indica, pide un texto obligatorio (p.ej. motivo de anulación). */
  pedirMotivo?: string;
  peligro?: boolean;
}

/** Devuelve true (o el motivo escrito) si el usuario confirma; undefined si cancela. */
@Component({
  selector: 'app-confirmar-dialog',
  imports: [MatDialogModule, MatButtonModule, MatFormFieldModule, MatInputModule, ReactiveFormsModule],
  template: `
    <h2 mat-dialog-title>{{ datos.titulo }}</h2>
    <mat-dialog-content>
      <p>{{ datos.mensaje }}</p>
      @if (datos.pedirMotivo) {
        <mat-form-field style="width:100%">
          <mat-label>{{ datos.pedirMotivo }}</mat-label>
          <input matInput [formControl]="motivo" maxlength="90" cdkFocusInitial />
        </mat-form-field>
      }
    </mat-dialog-content>
    <mat-dialog-actions align="end">
      <button mat-button mat-dialog-close>Cancelar</button>
      <button mat-flat-button [class.peligro]="datos.peligro" [disabled]="datos.pedirMotivo && motivo.invalid"
              (click)="confirmar()">{{ datos.accion ?? 'Aceptar' }}</button>
    </mat-dialog-actions>`,
  styles: `.peligro { --mat-button-filled-container-color: var(--mat-sys-error); }`,
})
export class ConfirmarDialog {
  protected readonly datos = inject<ConfirmarDatos>(MAT_DIALOG_DATA);
  private readonly ref = inject(MatDialogRef<ConfirmarDialog>);
  protected readonly motivo = new FormControl('', { nonNullable: true, validators: [Validators.required] });

  protected confirmar(): void {
    this.ref.close(this.datos.pedirMotivo ? this.motivo.value.trim() : true);
  }
}
