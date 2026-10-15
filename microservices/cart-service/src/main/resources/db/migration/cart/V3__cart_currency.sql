ALTER TABLE cart ADD COLUMN currency VARCHAR(3);

-- Existing catalog products use SEK; preserve the currency of pre-existing carts.
UPDATE cart SET currency = 'SEK' WHERE EXISTS (
    SELECT 1 FROM cart_items ci WHERE ci.cart_id = cart.id
);
