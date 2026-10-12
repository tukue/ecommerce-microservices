import { z } from "zod";

export const checkoutRequestSchema = z.object({
  cartId: z.number().int().positive(),
  idempotencyKey: z
    .string()
    .min(1)
    .max(64, "Idempotency key must not exceed 64 characters")
    .optional(),
});

export const orderLineResponseSchema = z.object({
  lineTotal: z.number().finite().nonnegative(),
  productId: z.number().int().positive(),
  productName: z.string().trim().min(1),
  quantity: z.number().int().positive(),
  unitPrice: z.number().finite().nonnegative(),
});

export const orderResponseSchema = z.object({
  cartId: z.number().int().positive(),
  currency: z.string().regex(/^[A-Z]{3}$/).optional(),
  id: z.number().int().positive(),
  orderDate: z.string().datetime({ local: true }),
  orderLines: z.array(orderLineResponseSchema).min(1),
  status: z.enum(["PENDING", "COMPLETED", "CANCELLED"]),
  total: z.number().finite().nonnegative(),
  userId: z.string().trim().min(1),
});

export const orderStatusSchema = z.enum(["PENDING", "COMPLETED", "CANCELLED"]);
export type OrderStatus = z.infer<typeof orderStatusSchema>;

export const ordersListResponseSchema = z.array(orderResponseSchema);
export type OrdersListResponse = z.infer<typeof ordersListResponseSchema>;

export type CheckoutRequestDto = z.infer<typeof checkoutRequestSchema>;
export type OrderResponseDto = z.infer<typeof orderResponseSchema>;
