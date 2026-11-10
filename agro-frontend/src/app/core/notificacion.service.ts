import { Injectable, inject } from '@angular/core';
import { HttpErrorResponse } from '@angular/common/http';
import { MatSnackBar } from '@angular/material/snack-bar';
import type { Problema } from '../api/tipos';

@Injectable({ providedIn: 'root' })
export class Notificacion {
  private readonly snack = inject(MatSnackBar);

  ok(mensaje: string): void {
    this.snack.open(mensaje, 'OK', { duration: 3500 });
  }

  error(err: unknown, porDefecto = 'Ocurrió un error inesperado'): void {
    this.snack.open(mensajeDeError(err, porDefecto), 'Cerrar', { duration: 7000, panelClass: 'snack-error' });
  }
}

/** Extrae el mensaje legible de un error HTTP (ProblemDetail del backend). */
export function mensajeDeError(err: unknown, porDefecto = 'Ocurrió un error inesperado'): string {
  if (err instanceof HttpErrorResponse) {
    if (err.status === 0) return 'No hay conexión con el servidor';
    const p = err.error as Problema | null;
    if (p?.errores && Object.keys(p.errores).length) {
      return Object.entries(p.errores).map(([c, m]) => `${c}: ${m}`).join(' · ');
    }
    return p?.detail ?? porDefecto;
  }
  return porDefecto;
}

/** Código de negocio del error (p.ej. EXISTENCIA_INSUFICIENTE), si existe. */
export function codigoDeError(err: unknown): string | undefined {
  return err instanceof HttpErrorResponse ? (err.error as Problema | null)?.code : undefined;
}
