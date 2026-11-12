import { Routes } from '@angular/router';
import { adminGuard } from './core/auth/admin.guard';
import { authGuard } from './core/auth/auth.guard';

export const routes: Routes = [
  {
    path: '',
    loadComponent: () =>
      import('./core/layout/public-layout/public-layout').then((module) => module.PublicLayout),
    children: [
      {
        path: '',
        pathMatch: 'full',
        title: 'DS Catalog',
        loadComponent: () => import('./features/home/home/home').then((m) => m.Home),
      },
      {
        path: 'cart',
        canActivate: [authGuard],
        title: 'Cart | DS Catalog',
        loadChildren: () =>
          import('./features/cart/cart.routes').then((module) => module.cartRoutes),
      },
      {
        path: 'catalog',
        title: 'Catalog | DS Catalog',
        loadChildren: () =>
          import('./features/catalog/catalog.routes').then((module) => module.catalogRoutes),
      },
      {
        path: '',
        loadChildren: () =>
          import('./features/orders/orders.routes').then((module) => module.ordersRoutes),
      },
    ],
  },
  {
    path: 'admin',
    canMatch: [adminGuard],
    loadChildren: () =>
      import('./features/admin/admin.routes').then((module) => module.adminRoutes),
  },
  { path: '**', redirectTo: '' },
];
