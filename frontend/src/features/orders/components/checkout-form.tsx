import { zodResolver } from "@hookform/resolvers/zod";
import { useRef, useState } from "react";
import { useForm } from "react-hook-form";

import {
  ApplicationError,
  createApplicationError,
} from "@/shared/errors/application-error";

import type { CartSummary, Order } from "../domain/order";
import {
  clearCheckoutAttempt,
  setCheckoutAttemptState,
} from "../../../shared/utils/checkout-attempt";
import {
  checkoutFormSchema,
  type CheckoutFormValues,
} from "../api/checkout-form-schema";

type CheckoutFormProps = Readonly<{
  cart: CartSummary;
  onConfirmed(order: Order): void | Promise<void>;
  submitOrder(input: {
    cartId: number;
    idempotencyKey: string;
  }): Promise<Order>;
}>;

export function CheckoutForm({
  cart,
  onConfirmed,
  submitOrder,
}: CheckoutFormProps) {
  const submittingRef = useRef(false);
  const [failureMessage, setFailureMessage] = useState<string>();
  const [submissionLocked, setSubmissionLocked] = useState(false);
  const form = useForm<CheckoutFormValues>({
    defaultValues: {},
    mode: "onChange",
    resolver: zodResolver(checkoutFormSchema),
  });

  async function onSubmit(values: CheckoutFormValues): Promise<void> {
    if (submittingRef.current) {
      return;
    }

    submittingRef.current = true;
    setSubmissionLocked(true);
    setFailureMessage(undefined);
    try {
      const order = await submitOrder({
        cartId: cart.id,
        idempotencyKey: setCheckoutAttemptState(cart.id, "SUBMITTING")
          .idempotencyKey,
        ...values,
      });
      setCheckoutAttemptState(cart.id, "SUCCEEDED");
      clearCheckoutAttempt(cart.id);
      await onConfirmed(order);
    } catch (error) {
      setCheckoutAttemptState(
        cart.id,
        error instanceof ApplicationError &&
          error.kind !== "service-unavailable" &&
          error.kind !== "timeout"
          ? "FAILED"
          : "AMBIGUOUS",
      );
      const applicationError =
        error instanceof ApplicationError
          ? error
          : createApplicationError("unexpected");
      setFailureMessage(applicationError.customerMessage);
    } finally {
      submittingRef.current = false;
      setSubmissionLocked(false);
    }
  }

  return (
    <form
      className="checkout-panel"
      aria-label="Checkout"
      onSubmit={form.handleSubmit(onSubmit)}
    >
      <p className="eyebrow">ORDER REVIEW</p>
      <h2>Checkout</h2>
      <p className="muted">Review your cart and place your order.</p>
      <p>
        Cart total: {cart.total.toFixed(2)} {cart.currency ?? "SEK"}
      </p>
      <ul aria-label="Cart summary">
        {cart.items.map((item) => (
          <li key={item.productId}>
            <span>
              {item.productName} x {item.quantity}
            </span>
            <strong>{item.lineTotal.toFixed(2)}</strong>
          </li>
        ))}
      </ul>
      <p className="checkout-note">
        The final amount is confirmed by the store. Your order is safe to retry
        if the connection drops.
      </p>
      {failureMessage && <p role="alert">{failureMessage}</p>}
      <button
        disabled={
          !form.formState.isValid ||
          form.formState.isSubmitting ||
          submissionLocked
        }
        type="submit"
      >
        {form.formState.isSubmitting
          ? "Starting secure payment..."
          : "Continue to payment"}
      </button>
    </form>
  );
}
