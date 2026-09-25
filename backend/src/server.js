import "dotenv/config";
import express from "express";
import crypto from "node:crypto";
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { parseTransaction } from "./parse.js";

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const DATA_DIR = path.join(__dirname, "..", "data");
const EVENTS_FILE = path.join(DATA_DIR, "events.jsonl");

const PORT = process.env.PORT || 3000;
const SECRET = process.env.WEBHOOK_SECRET;
const WEBHOOK_PATH = process.env.WEBHOOK_PATH || "/opennotify";

if (!SECRET) {
  console.error(
    "Thiếu WEBHOOK_SECRET trong .env — dừng lại để tránh chạy không xác thực."
  );
  process.exit(1);
}

fs.mkdirSync(DATA_DIR, { recursive: true });

const app = express();

// Dedupe trong bộ nhớ: app Android có thể gửi lại cùng một sự kiện nếu
// chưa nhận được phản hồi 2xx kịp lúc (mất mạng, restart giữa chừng...).
const seen = new Map(); // dedupeKey -> thời điểm nhận (ms)
const DEDUPE_TTL_MS = 10 * 60 * 1000;

function cleanupSeen() {
  const cutoff = Date.now() - DEDUPE_TTL_MS;
  for (const [k, t] of seen) if (t < cutoff) seen.delete(k);
}
setInterval(cleanupSeen, 60 * 1000).unref();

// Bắt buộc raw body (Buffer) trên đúng route webhook, vì chữ ký HMAC
// được tính trên nguyên văn bytes app đã gửi — parse JSON trước sẽ làm
// lệch chữ ký nếu thứ tự field/khoảng trắng khác đi.
app.use(WEBHOOK_PATH, express.raw({ type: "*/*", limit: "256kb" }));

app.post(WEBHOOK_PATH, (req, res) => {
  const raw = req.body; // Buffer
  const signature = req.header("X-Signature") || "";

  const expected = crypto.createHmac("sha256", SECRET).update(raw).digest("hex");

  let sigOk = false;
  try {
    sigOk =
      signature.length === expected.length &&
      crypto.timingSafeEqual(Buffer.from(signature, "hex"), Buffer.from(expected, "hex"));
  } catch {
    sigOk = false; // signature không phải hex hợp lệ
  }

  if (!sigOk) {
    console.warn("[opennotify] chữ ký không khớp, từ chối");
    return res.status(401).json({ error: "invalid signature" });
  }

  let event;
  try {
    event = JSON.parse(raw.toString("utf8"));
  } catch {
    return res.status(400).json({ error: "invalid json" });
  }

  const dedupeKey = `${event.package}|${event.key}|${event.post_time}`;
  if (seen.has(dedupeKey)) {
    console.log(`[opennotify] trùng, bỏ qua: ${dedupeKey}`);
    return res.status(200).json({ status: "duplicate" });
  }
  seen.set(dedupeKey, Date.now());

  const combined = [event.title, event.text, event.big_text, event.sub_text, event.lines]
    .filter(Boolean)
    .join(" ");
  const parsed = parseTransaction(combined);

  const record = { received_at: new Date().toISOString(), ...event, parsed };
  fs.appendFileSync(EVENTS_FILE, JSON.stringify(record) + "\n");

  console.log(`[opennotify] ${event.package} <- ${event.text || event.title}`);
  console.log(parsed ? `  -> parsed: ${JSON.stringify(parsed)}` : "  -> chưa parse được, xem parse.js");

  res.status(200).json({ status: "ok", parsed });
});

app.get("/health", (_req, res) => res.json({ ok: true }));

// Xem nhanh N sự kiện gần nhất để debug. KHÔNG bật public không có auth
// trong môi trường thật — endpoint này chỉ để chạy local/dev.
app.get("/events", (req, res) => {
  const limit = Math.min(Number(req.query.limit) || 20, 200);
  const lines = fs.existsSync(EVENTS_FILE)
    ? fs.readFileSync(EVENTS_FILE, "utf8").trim().split("\n").filter(Boolean)
    : [];
  const events = lines.slice(-limit).reverse().map((l) => JSON.parse(l));
  res.json(events);
});

app.listen(PORT, () => {
  console.log(`OpenNotify backend mẫu đang chạy: http://localhost:${PORT}${WEBHOOK_PATH}`);
});
