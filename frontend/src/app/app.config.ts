import {
  ApplicationConfig,
  inject,
  provideAppInitializer,
  provideBrowserGlobalErrorListeners,
} from '@angular/core';
import { provideRouter, withComponentInputBinding } from '@angular/router';
import {
  provideHttpClient,
  withFetch,
  withInterceptors,
  withXsrfConfiguration,
} from '@angular/common/http';
import Aura from '@primeuix/themes/aura';
import { definePreset } from '@primeuix/themes';
import { providePrimeNG } from 'primeng/config';
import { MessageService } from 'primeng/api';

import { routes } from './app.routes';
import { AuthService } from './core/auth/auth.service';
import { sessionExpiredInterceptor } from './core/auth/session-expired.interceptor';
import { CartStore } from './core/cart/cart-store';
import { LocalStorageCartStore } from './core/cart/local-storage-cart-store';

export const appConfig: ApplicationConfig = {
  providers: [
    { provide: CartStore, useClass: LocalStorageCartStore },
    MessageService,
    provideBrowserGlobalErrorListeners(),
    provideRouter(routes, withComponentInputBinding()),
    provideHttpClient(
      withFetch(),
      withXsrfConfiguration({ cookieName: 'XSRF-TOKEN', headerName: 'X-XSRF-TOKEN' }),
      withInterceptors([sessionExpiredInterceptor]),
    ),
    provideAppInitializer(() => inject(AuthService).load()),
    providePrimeNG({
      theme: {
        preset: definePreset(Aura, {
          semantic: {
            primary: {
              50: '#eff5ff',
              100: '#dbe8ff',
              200: '#bfd5ff',
              300: '#93b8ff',
              400: '#6696ff',
              500: '#407bff',
              600: '#2563eb',
              700: '#1d4ed8',
              800: '#1e40af',
              900: '#1e3a8a',
              950: '#172554',
            },
            colorScheme: {
              light: {
                surface: {
                  100: '#f2f2f2',
                  200: '#e1e1e1',
                  900: '#263238',
                },
                text: {
                  color: '#263238',
                  mutedColor: '#9e9e9e',
                },
                primary: {
                  color: '{primary.500}',
                  contrastColor: '#ffffff',
                  hoverColor: '{primary.600}',
                  activeColor: '{primary.700}',
                },
              },
              dark: {
                primary: {
                  color: '{primary.500}',
                  contrastColor: '#ffffff',
                  hoverColor: '{primary.400}',
                  activeColor: '{primary.300}',
                },
              },
            },
          },
          components: {
            card: {
              root: {
                borderRadius: '20px',
                shadow: '0px 4px 20px 0px rgba(0, 0, 0, 0.25)',
              },
              body: {
                padding: '0',
                gap: '0',
              },
            },
            inputtext: {
              root: {
                borderRadius: '10px',
                borderColor: '{surface.200}',
                placeholderColor: '{text.muted.color}',
              },
            },
            button: {
              root: {
                borderRadius: '10px',
              },
            },
          },
        }),
        options: {
          darkModeSelector: '.p-dark',
          cssLayer: {
            name: 'primeng',
            order: 'theme, base, primeng',
          },
        },
      },
    }),
  ],
};
