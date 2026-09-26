// Giả lập app OpenNotify gửi một thông báo đã ký, để test backend mà
// không cần điện thoại thật. Chạy: npm run test:send
// (đọc WEBHOOK_SECRET và TEST_URL từ biến môi trường / file .env)

import "dotenv/config";
import crypto from "node:crypto";

const URL = process.env.TEST_URL || "http://localhost:3000/opennotify";
const SECRET = process.env.WEBHOOK_SECRET;

if (!SECRET) {
  console.error("Thiếu WEBHOOK_SECRET (phải trùng với .env của server).");
  process.exit(1);
}

// Payload chuẩn OpenNotify Webhook v1 — xem docs/webhook-standard.md ở gốc repo.
const now = Date.now();
const title = "Biến động số dư";
const bodyText = "+50,000VND GD:123456 DH0001 chuyen tien test";
const event = {
  v: 1,
  source: "opennotify",
  pkg: "com.mbmobile",
  key: `test-${now}`,
  post_time: now,
  at: now,
  text: `${title} ${bodyText}`,
  title,
  body: bodyText,
  big_text: "",
  sub_text: "",
  lines: "",
};

const body = JSON.stringify(event);
const signature = crypto.createHmac("sha256", SECRET).update(body).digest("hex");

const res = await fetch(URL, {
  method: "POST",
  headers: { "Content-Type": "application/json", "X-Signature": signature },
  body,
});

console.log(res.status, await res.text());
