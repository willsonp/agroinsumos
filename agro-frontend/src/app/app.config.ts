import { ApplicationConfig, DEFAULT_CURRENCY_CODE, LOCALE_ID, inject, provideAppInitializer,
  provideBrowserGlobalErrorListeners } from '@angular/core';
import { registerLocaleData } from '@angular/common';
import localeEsDo from '@angular/common/locales/es-DO';
import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { provideRouter, withComponentInputBinding } from '@angular/router';
import { DateAdapter, provideNativeDateAdapter, MAT_DATE_LOCALE } from '@angular/material/core';
import { FechaAdapter } from './shared/fecha-adapter';
import { MAT_FORM_FIELD_DEFAULT_OPTIONS } from '@angular/material/form-field';
import { MatPaginatorIntl } from '@angular/material/paginator';
import { PaginadorEs } from './core/paginador-es';
import { firstValueFrom } from 'rxjs';
import { routes } from './app.routes';
import { authInterceptor } from './core/auth.interceptor';
import { AuthService } from './core/auth.service';

registerLocaleData(localeEsDo);

export const appConfig: ApplicationConfig = {
  providers: [
    provideBrowserGlobalErrorListeners(),
    provideRouter(routes, withComponentInputBinding()),
    provideHttpClient(withInterceptors([authInterceptor])),
    provideNativeDateAdapter(),
    { provide: DateAdapter, useClass: FechaAdapter },   // dd/mm/aaaa al escribir la fecha
    { provide: LOCALE_ID, useValue: 'es-DO' },
    { provide: MAT_DATE_LOCALE, useValue: 'es-DO' },
    { provide: DEFAULT_CURRENCY_CODE, useValue: 'DOP' },
    { provide: MatPaginatorIntl, useClass: PaginadorEs },
    { provide: MAT_FORM_FIELD_DEFAULT_OPTIONS, useValue: { appearance: 'outline', subscriptSizing: 'dynamic' } },
    // Recupera la sesión (refresh token guardado) antes de resolver la primera ruta
    provideAppInitializer(() => firstValueFrom(inject(AuthService).restaurar())),
  ],
};
