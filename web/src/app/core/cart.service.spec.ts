import { TestBed } from '@angular/core/testing';

import { CartService } from './cart.service';
import { MenuItem } from './models';

function item(id: string, name: string, price: number): MenuItem {
  return {
    id,
    categoryId: 'cat-1',
    categoryName: 'Mains',
    name,
    description: null,
    price,
    available: true,
    preparationMinutes: 15,
    imageUrl: null,
    updatedAt: new Date().toISOString(),
  };
}

describe('CartService', () => {
  let cart: CartService;

  beforeEach(() => {
    localStorage.clear();
    TestBed.configureTestingModule({});
    cart = TestBed.inject(CartService);
  });

  it('starts empty', () => {
    expect(cart.isEmpty()).toBeTrue();
    expect(cart.count()).toBe(0);
  });

  it('adds an item', () => {
    cart.add(item('a', 'Tilapia', 12.5));

    expect(cart.count()).toBe(1);
    expect(cart.estimatedTotal()).toBe(12.5);
  });

  it('merges a repeat of the same item into one line', () => {
    // Two lines for the same dish produce a confusing basket and a confusing
    // kitchen ticket.
    cart.add(item('a', 'Tilapia', 12.5));
    cart.add(item('a', 'Tilapia', 12.5), 2);

    expect(cart.lines().length).toBe(1);
    expect(cart.count()).toBe(3);
  });

  it('caps quantity at 50', () => {
    cart.add(item('a', 'Tea', 2), 100);

    expect(cart.lines()[0].quantity).toBe(50);
  });

  it('removes a line when its quantity reaches zero', () => {
    cart.add(item('a', 'Tea', 2));
    cart.setQuantity('a', 0);

    expect(cart.isEmpty()).toBeTrue();
  });

  it('produces the shape the orders API expects', () => {
    cart.add(item('a', 'Tilapia', 12.5), 2);
    cart.add(item('b', 'Tea', 2), 1);

    expect(cart.toOrderLines()).toEqual([
      { menuItemId: 'a', quantity: 2 },
      { menuItemId: 'b', quantity: 1 },
    ]);
  });

  it('survives a page reload', () => {
    // Losing a basket to an accidental refresh is the kind of small thing that
    // makes people give up on an order.
    cart.add(item('a', 'Tilapia', 12.5), 2);

    const reloaded = new CartService();

    expect(reloaded.count()).toBe(2);
  });

  it('recovers from corrupt stored data rather than crashing', () => {
    localStorage.setItem('dinehub.cart', 'not json');

    const recovered = new CartService();

    expect(recovered.isEmpty()).toBeTrue();
  });
});
