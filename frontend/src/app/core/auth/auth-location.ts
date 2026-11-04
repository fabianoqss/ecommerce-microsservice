import { DOCUMENT, inject, InjectionToken } from '@angular/core';

export const AUTH_LOCATION = new InjectionToken<Pick<Location, 'assign'>>('AUTH_LOCATION', {
  providedIn: 'root',
  factory: () => inject(DOCUMENT).location,
});
