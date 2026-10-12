import type { Order } from "../domain/order";
import type { OrderResponseDto } from "./order.schemas";

export function toOrder(dto: OrderResponseDto): Order {
  return {
    cartId: dto.cartId,
    currency: dto.currency ?? "SEK",
    id: dto.id,
    orderDate: dto.orderDate,
    orderLines: dto.orderLines,
    status: dto.status,
    total: dto.total,
  };
}
