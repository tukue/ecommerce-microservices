import { z } from "zod";

export const cartStatusSchema = z.enum(["OPEN", "CHECKED_OUT"]);

export const cartItemResponseSchema = z.object({
  id: z.number().int().positive(),
  price: z.number().finite().nonnegative(),
  productId: z.number().int().positive(),
  productName: z.string().trim().min(1),
  quantity: z.number().int().positive(),
});

export const cartResponseSchema = z.object({
  currency: z.string().regex(/^[A-Z]{3}$/).nullable().default(null),
  id: z.number().int().positive(),
  items: z.array(cartItemResponseSchema),
  status: cartStatusSchema,
});

export const addCartItemRequestSchema = z.object({
  productId: z.number().int().positive(),
  quantity: z.number().int().positive(),
});

export type AddCartItemRequest = z.infer<typeof addCartItemRequestSchema>;
export type CartItemResponse = z.infer<typeof cartItemResponseSchema>;
export type CartResponse = z.infer<typeof cartResponseSchema>;
