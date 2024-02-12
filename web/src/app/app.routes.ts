import { Routes } from '@angular/router';

import { authGuard, roleGuard } from './core/auth.guard';

/**
 * Routes are lazy-loaded per feature, so the kitchen bundle is not shipped to a
 * customer who will never open it.
 */
export const routes: Routes = [
  { path: '', pathMatch: 'full', redirectTo: 'menu' },

  {
    path: 'menu',
    title: 'Menu · DineHub',
    loadComponent: () =>
      import('./features/menu/menu.component').then((m) => m.MenuComponent),
  },
  {
    path: 'login',
    title: 'Sign in · DineHub',
    loadComponent: () =>
      import('./features/auth/login.component').then((m) => m.LoginComponent),
  },
  {
    path: 'cart',
    title: 'Your order · DineHub',
    canActivate: [roleGuard('CUSTOMER')],
    loadComponent: () =>
      import('./features/orders/cart.component').then((m) => m.CartComponent),
  },
  {
    path: 'orders',
    title: 'My orders · DineHub',
    canActivate: [roleGuard('CUSTOMER')],
    loadComponent: () =>
      import('./features/orders/orders.component').then((m) => m.OrdersComponent),
  },
  {
    path: 'kitchen',
    title: 'Kitchen · DineHub',
    canActivate: [roleGuard('KITCHEN', 'ADMIN')],
    loadComponent: () =>
      import('./features/kitchen/kitchen.component').then((m) => m.KitchenComponent),
  },
  {
    path: 'admin',
    title: 'Admin · DineHub',
    canActivate: [roleGuard('ADMIN')],
    loadComponent: () =>
      import('./features/admin/admin.component').then((m) => m.AdminComponent),
  },
  {
    path: 'notifications',
    title: 'Notifications · DineHub',
    canActivate: [authGuard],
    loadComponent: () =>
      import('./features/orders/notifications.component').then(
        (m) => m.NotificationsComponent,
      ),
  },

  { path: '**', redirectTo: 'menu' },
];
