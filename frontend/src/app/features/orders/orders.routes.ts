import { Routes } from '@angular/router';
import { authGuard } from '../../core/auth/auth.guard';

export const ordersRoutes: Routes = [
  {
    path: 'checkout',
    canActivate: [authGuard],
    title: 'Pedido | DS Catalog',
    loadComponent: () => import('./checkout/checkout').then((module) => module.Checkout),
  },
];
