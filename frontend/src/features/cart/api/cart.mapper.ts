import type { Cart, CartItem } from "../domain/cart";
import { cartResponseSchema, type CartResponse } from "./cart.schemas";

function toCartItem(item: CartResponse["items"][number]): CartItem {
  return {
    id: item.id,
    price: item.price,
    productId: item.productId,
    productName: item.productName,
    quantity: item.quantity,
  };
}

export function toCart(response: unknown): Cart {
  const cart = cartResponseSchema.parse(response);
  return {
    currency: cart.currency ?? null,
    id: cart.id,
    items: cart.items.map(toCartItem),
    status: cart.status,
  };
}
