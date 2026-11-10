import { Component, OnInit, inject } from '@angular/core';
import { Router } from '@angular/router';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { AuthService } from '../../core/auth.service';

/** Recibe los tokens del login social en el fragmento (#access_token=...&refresh_token=...). */
@Component({
  selector: 'app-oauth-callback',
  imports: [MatProgressSpinnerModule],
  template: `<div style="display:grid;place-items:center;height:100vh"><mat-spinner /></div>`,
})
export class OAuthCallback implements OnInit {
  private readonly auth = inject(AuthService);
  private readonly router = inject(Router);

  ngOnInit(): void {
    const params = new URLSearchParams(window.location.hash.slice(1));
    const error = new URLSearchParams(window.location.search).get('error');
    // Quitar los tokens de la barra de direcciones y del historial
    history.replaceState(null, '', window.location.pathname);
    const access = params.get('access_token');
    const refresh = params.get('refresh_token');
    if (error || !access || !refresh) {
      this.router.navigate(['/login'], { queryParams: { error: error ?? 'oauth2_fallido' } });
      return;
    }
    this.auth.aceptarTokensOAuth(access, refresh).subscribe({
      next: () => this.router.navigate(['/inicio']),
      error: () => this.router.navigate(['/login'], { queryParams: { error: 'oauth2_fallido' } }),
    });
  }
}
