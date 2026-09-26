# OpenNotify backend (mẫu)

Server Node.js/Express tối giản để nhận webhook từ app **OpenNotify**
(xem `../app`): verify chữ ký HMAC-SHA256, chống trùng lặp, thử parse số
tiền/mã đơn bằng regex, ghi log ra file để xem lại. Đây là **hàng mẫu để
bạn tự mở rộng**, không phải service production sẵn dùng.

Đây là bên nhận chế độ **HMAC** của chuẩn OpenNotify Webhook v1 — định dạng payload, chữ ký
và mã trả lời nằm ở [`../docs/webhook-standard.md`](../docs/webhook-standard.md).

Đã tự test end-to-end trong quá trình viết (health check, sự kiện hợp
lệ, sự kiện trùng, sai chữ ký, endpoint xem log) — xem code để biết
chính xác hành vi từng trường hợp.

## Chạy thử

```bash
cd backend
npm install
cp .env.example .env
# mở .env, đặt WEBHOOK_SECRET trùng khớp với "Webhook Secret" trong app OpenNotify
npm start
```

Server chạy tại `http://localhost:3000/opennotify` (đổi `PORT`/`WEBHOOK_PATH`
trong `.env` nếu cần).

## Test không cần điện thoại

```bash
npm run test:send
```

Script này tự ký một sự kiện giả lập gửi tới server (đọc `WEBHOOK_SECRET`
từ `.env`), dùng để kiểm tra nhanh trước khi đụng tới điện thoại thật.
Đặt `TEST_URL=...` nếu server không chạy ở `localhost:3000`.

## Cho điện thoại thật gọi vào

Điện thoại cần gọi được `WEBHOOK_URL` qua Internet, không phải qua
`localhost` (localhost trên máy chạy server không phải localhost trên
điện thoại). Vài lựa chọn khi đang phát triển:

- **Cloudflare Tunnel:** `cloudflared tunnel --url http://localhost:3000`
  → dùng URL `https://xxxx.trycloudflare.com/opennotify` làm Webhook URL
  trong app.
- **ngrok:** `ngrok http 3000` → tương tự, dùng URL ngrok + `/opennotify`.
- **Deploy thật** (VPS, Render, Railway...) nếu muốn chạy lâu dài thay vì
  tunnel tạm thời.

## Endpoint

- `POST /opennotify` — nhận sự kiện từ app. Yêu cầu header `X-Signature` =
  `hex(HMAC_SHA256(WEBHOOK_SECRET, raw_body))`. Trả `401` nếu sai chữ ký,
  `200 {"status":"duplicate"}` nếu đã thấy `pkg+key+post_time` này
  trong 10 phút gần nhất, `200 {"status":"ok","parsed":...}` nếu ghi
  nhận thành công.
- `GET /health` — kiểm tra server còn sống.
- `GET /events?limit=20` — xem N sự kiện gần nhất đã ghi nhận (đọc từ
  `data/events.jsonl`). **Chỉ để debug/local** — endpoint này không có
  xác thực, đừng để public không kiểm soát trong môi trường thật.

## Dữ liệu lưu

Mỗi sự kiện hợp lệ (kể cả chưa parse được) được append vào
`data/events.jsonl` (một dòng JSON mỗi sự kiện, gồm cả kết quả `parsed`).
File này bị gitignore, không commit lên repo.

## Parser (`src/parse.js`) — CẦN chỉnh theo dữ liệu thật

Regex trong file này là **mẫu đoán trước**, chưa xác nhận bằng dữ liệu
thật từ MBBank hay bất kỳ nguồn nào khác:

```js
const AMOUNT_RE = /([+-])\s*([\d.,]+)\s*(?:VND|VNĐ|đ)\b/i;
const ORDER_RE = /\bDH\d+\b/i;
```

**Cách lấy mẫu thật từ điện thoại:** mở app OpenNotify → **Xem nguồn
thông báo đã ghi nhận** → chọn nguồn (ví dụ `com.mbmobile`) → bấm vào
một thông báo thật → copy nội dung `title`/`body`/`big_text`/`sub_text`/
`lines` → chỉnh `AMOUNT_RE`/`ORDER_RE` (hoặc viết lại `parseTransaction`)
theo đúng định dạng đó.

## Dedupe

Dedupe hiện tại là **in-memory**, key theo `pkg|key|post_time`, giữ
trong 10 phút rồi tự dọn. Nghĩa là nếu restart server, các sự kiện gửi
lại ngay sau đó sẽ không còn bị coi là trùng nữa — chấp nhận được cho
mục đích mẫu/nội bộ, nhưng nếu cần dedupe bền vững qua restart, chuyển
sang lưu `seen` trong SQLite/Redis thay vì `Map` trong bộ nhớ.

## Bảo mật khi đưa lên môi trường thật

Đây là bản mẫu, còn thiếu nhiều thứ trước khi dùng nghiêm túc: rate
limiting, HTTPS bắt buộc (không chỉ qua tunnel dev), xác thực cho
`/events`, giám sát/alerting khi verify chữ ký thất bại liên tục (dấu
hiệu có người dò secret), và một nơi lưu bền vững hơn JSONL (Postgres,
SQLite...) nếu khối lượng sự kiện lớn.
