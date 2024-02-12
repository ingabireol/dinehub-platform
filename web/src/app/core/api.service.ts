import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';

import { environment } from '../../environments/environment';
import {
  Category,
  KitchenTicket,
  MenuItem,
  Notification,
  Order,
  Page,
} from './models';

/**
 * Every call the app makes, in one place.
 *
 * <p>Components inject this rather than HttpClient, so a URL change is one edit
 * and no component knows the API's shape beyond the model types.
 */
@Injectable({ providedIn: 'root' })
export class ApiService {
  private readonly http = inject(HttpClient);
  private readonly base = environment.apiUrl;

  // --- Menu ---------------------------------------------------------------

  categories(): Observable<Category[]> {
    return this.http.get<Category[]>(`${this.base}/menu/categories`);
  }

  menuItems(availableOnly = false): Observable<MenuItem[]> {
    return this.http.get<MenuItem[]>(
      `${this.base}/menu/items?availableOnly=${availableOnly}`,
    );
  }

  createMenuItem(body: {
    categoryId: string;
    name: string;
    description?: string;
    price: number;
    preparationMinutes: number;
  }): Observable<MenuItem> {
    return this.http.post<MenuItem>(`${this.base}/menu/items`, body);
  }

  setItemAvailability(id: string, available: boolean): Observable<MenuItem> {
    return this.http.patch<MenuItem>(`${this.base}/menu/items/${id}/availability`, {
      available,
    });
  }

  deleteMenuItem(id: string): Observable<void> {
    return this.http.delete<void>(`${this.base}/menu/items/${id}`);
  }

  // --- Orders -------------------------------------------------------------

  placeOrder(body: {
    items: { menuItemId: string; quantity: number }[];
    deliveryAddress?: string;
    notes?: string;
  }): Observable<Order> {
    return this.http.post<Order>(`${this.base}/orders`, body);
  }

  myOrders(page = 0, size = 20): Observable<Page<Order>> {
    return this.http.get<Page<Order>>(
      `${this.base}/orders/mine?page=${page}&size=${size}`,
    );
  }

  order(id: string): Observable<Order> {
    return this.http.get<Order>(`${this.base}/orders/${id}`);
  }

  cancelOrder(id: string, reason?: string): Observable<Order> {
    return this.http.post<Order>(`${this.base}/orders/${id}/cancel`, { reason });
  }

  // --- Kitchen ------------------------------------------------------------

  kitchenBoard(): Observable<KitchenTicket[]> {
    return this.http.get<KitchenTicket[]>(`${this.base}/kitchen/board`);
  }

  advanceTicket(
    id: string,
    status: 'PREPARING' | 'READY' | 'DELIVERED',
  ): Observable<KitchenTicket> {
    return this.http.patch<KitchenTicket>(`${this.base}/kitchen/tickets/${id}/status`, {
      status,
    });
  }

  // --- Notifications ------------------------------------------------------

  notifications(): Observable<Page<Notification>> {
    return this.http.get<Page<Notification>>(`${this.base}/notifications`);
  }

  unreadCount(): Observable<{ unread: number }> {
    return this.http.get<{ unread: number }>(`${this.base}/notifications/unread-count`);
  }

  markAllRead(): Observable<{ updated: number }> {
    return this.http.post<{ updated: number }>(
      `${this.base}/notifications/read-all`,
      {},
    );
  }
}
