import test from "node:test";
import assert from "node:assert/strict";
import { balance, loadWallet, recordPurchase, saveWallet, toCents } from "../src/main/resources/static/checkout-wallet.js";

const order = (price, id = "order-1", listingId = "listing-1") => ({ id, listingId, price, status: "COMPLETED" });

test("a new checkout session starts with exactly $100", () => {
  assert.equal(balance(loadWallet({ getItem: () => null })), 10000);
});

test("prices use integer cents and reject invalid or fractional-cent amounts", () => {
  assert.equal(toCents("1.23"), 123);
  assert.equal(toCents(19.99), 1999);
  for (const price of [0, -1, NaN, Infinity, "invalid", 0.001, 0.00000001]) {
    assert.throws(() => toCents(price));
  }
});

test("a confirmed purchase reduces balance without mutating its prior state", () => {
  const initial = { purchases: [] };
  const paid = recordPurchase(initial, order(1.23));
  assert.equal(balance(paid), 9877);
  assert.equal(balance(initial), 10000);
});

test("receipt replay and duplicate listing do not deduct twice", () => {
  const paid = recordPurchase({ purchases: [] }, order(19.99));
  assert.equal(recordPurchase(paid, order(19.99)), paid);
  assert.equal(recordPurchase(paid, order(19.99, "order-2")), paid);
  assert.equal(balance(paid), 8001);
});

test("exact balance succeeds, insufficient balance cannot be spent", () => {
  const initial = { purchases: [] };
  assert.equal(balance(recordPurchase(initial, order(100))), 0);
  assert.throws(() => recordPurchase(initial, order(100.01)), /enough balance/);
  const paid = recordPurchase(initial, order(54));
  assert.throws(() => recordPurchase(paid, order(47, "order-2", "listing-2")), /enough balance/);
  assert.equal(balance(paid), 4600);
});

test("an incomplete order does not change balance", () => {
  const wallet = { purchases: [] };
  assert.throws(() => recordPurchase(wallet, { ...order(10), status: "PENDING" }));
  assert.throws(() => recordPurchase(wallet, { price: 10, status: "COMPLETED" }));
  assert.equal(balance(wallet), 10000);
});

test("session storage preserves the receipt and balance across reloads", () => {
  let raw;
  const storage = { getItem: () => raw, setItem: (_, value) => { raw = value; } };
  const paid = recordPurchase({ purchases: [] }, order(27.50));
  saveWallet(storage, paid);
  assert.deepEqual(loadWallet(storage), paid);
  assert.equal(balance(loadWallet(storage)), 7250);
});

test("blocked, corrupt, duplicate, and overspent storage recover safely", () => {
  for (const raw of ["broken", "null", "{}", '{"purchases":[null]}',
    JSON.stringify({ purchases: [{ id: "a", listingId: "x", cents: -100 }] }),
    JSON.stringify({ purchases: [{ id: "a", listingId: "x", cents: 10100 }] }),
    JSON.stringify({ purchases: [{ id: "a", listingId: "x", cents: 100 }, { id: "a", listingId: "x", cents: 100 }] })]) {
    assert.equal(balance(loadWallet({ getItem: () => raw })), 10000);
  }
  assert.equal(balance(loadWallet(null)), 10000);
  assert.doesNotThrow(() => saveWallet(null, { purchases: [] }));
});
