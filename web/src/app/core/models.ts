/** API shapes, mirroring the server DTOs. */

export type Role = 'CUSTOMER' | 'KITCHEN' | 'ADMIN';

export interface User {
  id: string;
  email: string;
  fullName: string;
  role: Role;
  createdAt: string;
}

export interface TokenResponse {
  accessToken: string;
  refreshToken: string;
  tokenType: string;
  expiresInSeconds: number;
  user: User;
}

export interface Category {
  id: string;
  name: string;
  description: string | null;
  displayOrder: number;
  itemCount: number;
}

export interface MenuItem {
  id: string;
  categoryId: string;
  categoryName: string;
  name: string;
  description: string | null;
  price: number;
  available: boolean;
  preparationMinutes: number;
  imageUrl: string | null;
  updatedAt: string;
}

export type OrderStatus =
  | 'PLACED'
  | 'PAID'
  | 'PREPARING'
  | 'READY'
  | 'DELIVERED'
  | 'CANCELLED';

export interface OrderLine {
  menuItemId: string;
  itemName: string;
  unitPrice: number;
  quantity: number;
  lineTotal: number;
}

export interface Order {
  id: string;
  customerId: string;
  customerEmail: string;
  status: OrderStatus;
  totalAmount: number;
  deliveryAddress: string | null;
  notes: string | null;
  cancellationReason: string | null;
  placedAt: string;
  updatedAt: string;
  items: OrderLine[];
}

export interface Page<T> {
  content: T[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
  last: boolean;
}

export interface KitchenTicket {
  id: string;
  orderId: string;
  itemsSummary: string;
  itemCount: number;
  status: 'QUEUED' | 'PREPARING' | 'READY' | 'DELIVERED' | 'CANCELLED';
  claimedBy: string | null;
  queuedAt: string;
  startedAt: string | null;
  readyAt: string | null;
  waitingSeconds: number;
  preparationSeconds: number | null;
}

export interface Notification {
  id: string;
  type: string;
  title: string;
  message: string;
  relatedOrderId: string | null;
  read: boolean;
  createdAt: string;
}

/** The error shape every service returns. One handler, not seven. */
export interface ApiError {
  timestamp: string;
  status: number;
  error: string;
  message: string;
  path: string;
  traceId: string;
  violations?: { field: string; message: string }[];
}

/** An item in the cart, before it becomes an order line. */
export interface CartItem {
  item: MenuItem;
  quantity: number;
}
