# Chuẩn OpenNotify Webhook v1

OpenNotify đọc thông báo của các app được chọn trên điện thoại và đẩy những thông báo khớp bộ lọc
tới **webhook**. Tài liệu này là **chuẩn mở** do OpenNotify đặt ra: bất kỳ bên nhận nào (backend
riêng, máy quầy bán hàng, dịch vụ bên thứ ba…) làm đúng tài liệu này là nhận được, không cần thỏa
thuận riêng với OpenNotify.

Có hai điều người dùng cần biết:

- **Một webhook là một bộ cấu hình** (mục 1). Nhập tay hay quét mã QR đều ra đúng bộ đó; mã QR chỉ
  là **tiện ích nhập nhanh**, không có thứ gì QR làm được mà nhập tay không làm được.
- **Payload và cách gửi giống nhau cho mọi bên nhận** (mục 3–5); chỉ khác ở chế độ bảo mật mà bên
  nhận chọn.

Bên nhận đã có: `backend/` trong repo này (chế độ `hmac`, hàng mẫu) và máy quầy mapchat (chế độ
`aes-gcm`, xem `V:\vibeBoss\mapchat\docs\10-opennotify.md`).

---

## 1. Cấu hình một webhook

| Trường | Kiểu | Ràng buộc | Ý nghĩa |
|---|---|---|---|
| `name` | chuỗi | tùy chọn | tên hiển thị trong app; trống thì dùng tên máy chủ của `url` |
| `url` | chuỗi | bắt buộc, `https://` (bản thử có thể `http://`) | nơi POST |
| `security` | `hmac` \| `aes-gcm` | bắt buộc | cách bảo vệ gói tin (mục 4) |
| `secret` | chuỗi | bắt buộc | `hmac`: chuỗi bí mật bất kỳ. `aes-gcm`: khóa **32 byte base64url không đệm** (đúng 43 ký tự) |
| `id` | chuỗi | `^[A-Za-z0-9_-]{1,64}$`; **bắt buộc với `aes-gcm`**, tùy chọn với `hmac` | mã định danh endpoint, đi dạng rõ để bên nhận biết gói tin của ai |
| `patterns` | mảng regex | tùy chọn | rỗng = nhận mọi thông báo của app đó |
| `match` | `any` \| `all` | mặc định `any` | khớp một trong / tất cả các pattern |
| `normalize` | bool | xem mục 2 | so pattern trên chuỗi đã viết hoa và bỏ mọi ký tự không phải chữ/số |

Thêm một luật nằm ngoài bảng: **mỗi webhook gắn với một app nguồn** do người dùng chọn trên điện thoại
(ví dụ app ngân hàng). Đây là thứ duy nhất không nằm trong cấu hình, vì chỉ điện thoại biết app nào
là app nào. Bên nhận dựa vào thông báo "tiền về" thì chủ điện thoại phải chọn đúng app ngân hàng —
app khác có thể tự dựng thông báo giả có nội dung tùy ý.

**`secret` là bí mật:** OpenNotify không hiện nó ra màn hình dạng rõ, không ghi log, không sao lưu
(`allowBackup=false`). Ai có `secret` là giả được gói tin gửi tới bên nhận đó.

## 2. Mã QR cấu hình (tiện ích)

Mã QR chứa **một object JSON** mô tả đúng các trường ở mục 1:

| Bao bì | Giá trị |
|---|---|
| Chế độ QR | Byte mode |
| Bảng mã | UTF-8, **không cần ECI** — OpenNotify luôn giải bằng UTF-8 |
| Nội dung | một object JSON, không BOM |

| Khóa trong QR | Tương ứng |
|---|---|
| `v` | **bắt buộc, đúng bằng `1`**. Khác `1` → app báo "cần cập nhật OpenNotify", không đoán |
| `name`, `url`, `id`, `match`, `normalize` | như mục 1 |
| `security` | như mục 1; **bỏ trống thì suy ra**: có `k` → `aes-gcm`, có `secret` → `hmac` |
| `k` | `secret` của chế độ `aes-gcm` (tên ngắn cho QR gọn) |
| `secret` | `secret` của chế độ `hmac` |
| `patterns` / `pattern` | mảng regex, hoặc một regex duy nhất |

Mặc định khi QR không ghi: `match = any`, **`normalize = true`**. Mọi khóa lạ bị bỏ qua (bản sau
có thể thêm khóa mà app cũ vẫn đọc được). QR được kiểm **đúng bằng các luật của form nhập tay** —
sai ở đâu thì báo ngay lúc quét.

Quét lại một mã QR có cùng `url` + `id` với webhook đã có trong cùng app → **cập nhật** webhook đó,
không tạo bản thứ hai.

Ví dụ `aes-gcm` (đây cũng chính là mã máy quầy mapchat đang in):

```json
{"v":1,"name":"Trà Sữa Thử Nghiệm","url":"https://mapchat.vn/api/notify","id":"ELVH4I88_SAnYBhm6M64RA","k":"D1w-geNX-6F_cwlffbMqkMI7XXFUedAZTGMSssjd7cI","pattern":"MC[A-HJ-NP-Z2-9]{6}"}
```

Ví dụ `hmac`:

```json
{"v":1,"name":"Server kế toán","url":"https://ketoan.example.com/opennotify","secret":"doi-chuoi-nay","patterns":["\\+[\\d.,]+ ?VND"],"normalize":false}
```

> **Mã QR chứa `secret`/`k` — đừng chụp màn hình gửi đi.** Lộ thì đổi secret ở bên nhận rồi quét lại.

## 3. Payload (bản rõ — giống nhau ở mọi chế độ)

```json
{
  "v": 1,
  "source": "opennotify",
  "pkg": "com.mbmobile",
  "key": "0|com.mbmobile|123|null|10123",
  "post_time": 1790000000000,
  "at": 1790000000350,
  "text": "Biến động số dư TK 0123... +150,000VND luc 14:02 ND: MCK7P2QX 8899",
  "title": "Biến động số dư",
  "body": "TK 0123... +150,000VND luc 14:02 ND: MCK7P2QX 8899",
  "big_text": "",
  "sub_text": "",
  "lines": ""
}
```

| Trường | Ý nghĩa |
|---|---|
| `v` | phiên bản payload, hiện là `1` |
| `pkg` | package của app đã phát thông báo |
| `key`, `post_time` | khóa và thời điểm Android gán cho thông báo — `pkg`+`key`+`post_time` dùng để chống trùng |
| `at` | thời điểm OpenNotify nhận thông báo (ms) |
| `text` | **nội dung đầy đủ**: nối `title`, `body`, `big_text`, `sub_text`, `lines` (cái nào có), cách nhau một dấu cách. Đây cũng là chuỗi mà `patterns` được so |
| `title`, `body`, `big_text`, `sub_text`, `lines` | các phần thô của thông báo (`lines` nối bằng ` \| `) |

Bên nhận **chỉ nên dựa vào `text`** nếu chỉ cần nội dung; các trường thô có thể bị bỏ ở chế độ
`aes-gcm` khi gói tin quá lớn (mục 4). Bên nhận phải bỏ qua trường lạ.

## 4. Truyền

Mọi yêu cầu là `POST {url}` với `Content-Type: application/json; charset=utf-8`.

**`hmac`** — thân là payload JSON.

```
X-Signature: hex(HMAC_SHA256(secret, raw_body))
X-OpenNotify-Id: <id>          (chỉ khi webhook có id)
```

Bên nhận tính lại HMAC trên **đúng bytes thân nhận được** (không parse JSON trước) và so bằng hàm
so sánh constant-time.

**`aes-gcm`** — thân là

```json
{ "id": "<id>", "iv": "<base64url>", "ct": "<base64url>" }
```

- AES-256-GCM, khóa = `secret` giải base64url (32 byte).
- `iv`: 12 byte ngẫu nhiên **mới cho mỗi yêu cầu**, kể cả khi gửi lại cùng một thông báo.
- `ct` = mã hóa của payload JSON (UTF-8), **nhãn xác thực 16 byte nằm cuối** (mặc định của
  `javax.crypto` và của `createDecipheriv` trong Node).
- base64url **không đệm**.
- Thân tối đa **2 KB**. Vượt thì OpenNotify bỏ các trường thô trước, sau đó mới cắt dần đuôi `text`.

Vì GCM là mã hóa có xác thực, chế độ này **không kèm HMAC**: sai khóa hay sửa một byte đều bị bên
nhận loại ở bước kiểm nhãn. Bên chuyển tiếp ở giữa (nếu có) không đọc được nội dung.

## 5. Trả lời — OpenNotify làm gì

Áp như nhau cho mọi chế độ:

| Bên nhận trả | OpenNotify |
|---|---|
| 2xx | xong |
| 404, 410 | **ngừng gửi** tới webhook đó, bỏ các lượt đang chờ, báo người dùng sửa cấu hình / quét lại QR. Lưu lại cấu hình là gửi tiếp |
| 408, 429, 5xx, lỗi mạng | giữ lại và thử lại: ngay lập tức 0s → 5s → 15s, sau đó khoảng 15 phút một lần |
| 4xx khác (400, 401, 413…) | bỏ gói đó, không thử lại |
| chưa gửi được sau **6 giờ** | bỏ |

Thông báo được đọc bù khi app kết nối lại mà đã nằm trên máy quá 6 giờ thì chỉ lưu để xem, không gửi.

Bên nhận muốn điện thoại **ngừng hẳn** (ví dụ đã đổi khóa) thì trả `404` cho `id` cũ.

## 6. Lọc ở điện thoại

1. Chỉ thông báo của **app nguồn** gắn với webhook.
2. Khớp `patterns` theo `match` trên `text` (mục 3), không phân biệt hoa/thường. Với
   `normalize = true`, `text` được viết hoa và bỏ mọi ký tự không phải chữ/số trước khi so — hợp
   với mã kiểu `MCK7P2QX` mà ngân hàng hay chèn dấu cách hoặc xuống dòng vào giữa. Viết pattern cho
   chuỗi đã chuẩn hóa (không dấu cách, không `+`, không `.`).

Pattern là **bộ lọc để đỡ gửi thừa**, không phải cơ chế bảo mật. Chống giả mạo là việc của luật
chọn app nguồn (mục 1) và của `secret`.

## 7. Ổn định

- `v: 1` (QR) và `v: 1` (payload) không đổi ý nghĩa các trường đã có. Thêm trường mới vẫn giữ
  `v: 1` — bên đọc phải bỏ qua trường lạ. Chỉ khi **đổi hoặc bỏ** một trường mới tăng `v`.
- Chế độ bảo mật mới (nếu có) là một giá trị `security` mới; app cũ gặp giá trị lạ sẽ từ chối rõ ràng.

## 8. Trong code OpenNotify

| Phần | Ở đâu |
|---|---|
| Cấu hình + luật kiểm (chung cho nhập tay và QR) | `WebhookConfig.kt` — `WebhookConfig.validate()` |
| Đọc mã QR | `WebhookConfig.kt` — `WebhookQr.parse()` (ép UTF-8, sửa chữ vỡ ISO-8859-1) |
| Form nhập tay / quét QR | `WebhookEditActivity.kt` |
| Payload | `Payload.kt` |
| Chế độ `aes-gcm` | `AesGcmEnvelope.kt` |
| Gửi, phân loại mã trả lời, thử lại | `Sender.kt`, `NotifyListenerService.relay()`, `OutboxWorker.kt` |
| Lọc | `PatternMatcher.kt` — `inputFor()` |
| Bên nhận mẫu (`hmac`) | `backend/` |
