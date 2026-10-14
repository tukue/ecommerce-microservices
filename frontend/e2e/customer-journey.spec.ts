import { expect, test } from "@playwright/test";
import { addProductToCart, signIn } from "./helpers";

test("authenticated customer journey", async ({ page }) => {
  await signIn(page);
  await addProductToCart(page);
  await page.goto("/cart");
  await page.getByRole("button", { name: "Increase quantity" }).click();
  await page.getByRole("link", { name: "Proceed to Checkout" }).click();
  await page.getByRole("button", { name: "Continue to payment" }).click();
  await expect(page).toHaveURL(/\/confirmation\/\d+$/);
  await page.reload();
  await expect(page.getByText(/^Total:/)).toBeVisible();
  await page.goto("/orders");
  await expect(page.getByRole("table")).toBeVisible();
});
