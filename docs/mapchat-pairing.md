# Ghép đôi với mapchat — quy chuẩn phía OpenNotify

Chép từ hợp đồng giữa hai bên ở repo mapchat: `V:\vibeBoss\mapchat\docs\10-opennotify.md`
(mục 3b, 5, 5b, 6 và phần cảnh báo ở mục 9). **Bản bên mapchat là nguồn sự thật**; nguồn sự thật
trong code của mapchat là `NotifyPairing` ở `packages/protocol/src/index.ts`. Nếu hai bản lệch
nhau thì chép lại từ bên đó, đừng sửa tay ở đây.

Bối cảnh ngắn: máy quầy mapchat hiện một mã QR (**Cài đặt → Xác nhận thanh toán tự động → Hiện
mã QR**). OpenNotify quét mã đó, lọc thông báo "tiền về" của app ngân hàng có mã đơn mapchat,
mã hóa AES-256-GCM bằng khóa trong mã QR rồi gửi lên mapchat; mapchat chỉ chuyển tiếp, không giải
mã được. Máy quầy giải mã, khớp mã đơn + số tiền. Việc OpenNotify làm cụ thể ra sao nằm ở mục cuối.

---

## Schema mã QR (bản chuẩn)

Đây là **hợp đồng**. Phía mapchat cam kết sinh đúng như mô tả; phía OpenNotify cứ theo đây mà đọc.

### Bao bì

| Thứ | Giá trị |
|---|---|
| Chế độ mã hóa QR | **Byte mode** |
| Bảng mã | **UTF-8**, **KHÔNG có ECI** (xem cảnh báo dưới) |
| Sửa lỗi | `M` |
| Nội dung | một chuỗi **JSON object**, không xuống dòng, không BOM |
| Kích thước điển hình | 192 byte → QR phiên bản 10 (57×57 ô) |
| Tối đa | 247 byte → QR phiên bản 11 (61×61 ô), khi tên tiệm 40 ký tự tiếng Việt |
| Tối thiểu | 168 byte → QR phiên bản 9 (53×53 ô) |

(Ba con số trên là đo thật: sinh mã rồi giải ngược lại, chuỗi khớp nguyên văn cả ba trường hợp.)

> **⚠️ Bẫy encoding — đọc kỹ trước khi viết phần quét.**
> Mã QR **không mang ECI**, nên không có dấu hiệu nào trong mã báo "bytes này là UTF-8". Thư viện
> quét trên Android (ZXing và các bản dẫn xuất) khi không thấy ECI sẽ **tự đoán**, và thường rơi về
> ISO-8859-1 → tên tiệm hiện thành `Trà Sá»¯a`.
> Cách xử lý: ép bảng mã khi giải, ví dụ ZXing dùng
> `hints[DecodeHintType.CHARACTER_SET] = "UTF-8"`, hoặc tự lấy raw bytes rồi
> `String(bytes, Charsets.UTF_8)`.
> Chỉ `name` có ký tự ngoài ASCII, nên sai cùng lắm là tên hiển thị bị vỡ — nhưng vỡ ngay màn hình
> đầu tiên người dùng nhìn thấy.

### Các trường

Object có **đúng 6 trường**, tất cả **bắt buộc**, tất cả kiểu chuỗi trừ `v`:

| Trường | Kiểu | Ràng buộc | Bí mật? | Dùng để làm gì |
|---|---|---|---|---|
| `v` | number | **đúng bằng `1`** | không | phiên bản định dạng |
| `name` | string | 1–40 ký tự Unicode, có dấu tiếng Việt | không | **chỉ hiển thị**, không lọc gì |
| `url` | string | URL tuyệt đối, `https://` (bản dev có thể là `http://`) | không | nơi POST gói tin |
| `id` | string | **đúng 22 ký tự**, `^[A-Za-z0-9_-]{22}$` (16 byte base64url) | không | mã ghép, đi dạng rõ trong mọi gói tin |
| `k` | string | **đúng 43 ký tự**, `^[A-Za-z0-9_-]{43}$` (32 byte base64url) | **CÓ** | khóa AES-256-GCM |
| `pattern` | string | regex POSIX/PCRE cơ bản | không | lọc nội dung thông báo |

### Ví dụ đầy đủ

```json
{"v":1,"name":"Trà Sữa Thử Nghiệm","url":"https://mapchat.vn/api/notify","id":"ELVH4I88_SAnYBhm6M64RA","k":"D1w-geNX-6F_cwlffbMqkMI7XXFUedAZTGMSssjd7cI","pattern":"MC[A-HJ-NP-Z2-9]{6}"}
```

### Luật đọc — app PHẢI làm đúng

1. **Giải chuỗi bằng UTF-8** (xem cảnh báo trên).
2. **`v` khác `1` → từ chối**, báo *"cần cập nhật OpenNotify"*. Không đoán, không cố đọc tiếp.
3. **Bỏ qua mọi trường lạ.** Bản sau có thể thêm trường mới; app cũ phải vẫn chạy được. Đừng dùng
   kiểu parse "chặt" làm lỗi khi gặp trường không biết.
4. **Kiểm `id` và `k` bằng đúng regex ở trên** ngay lúc quét. Sai độ dài = mã QR hỏng hoặc quét
   nhầm cái khác — báo lỗi ngay, đừng để tới lúc gửi mới biết.
5. **`k` giải base64url phải ra đúng 32 byte.** Dùng `Base64.URL_SAFE or Base64.NO_PADDING`.
6. **`url` không được hardcode.** Bản dev, bản thử và bản thật khác nhau.
7. **Quét lại cùng một `id` = cập nhật cặp cũ**, không tạo cặp thứ hai. `id` là khóa chính phía app.
8. **Không log `k`, không hiện `k` ra màn hình, không backup `k` lên cloud.** Ai có `k` là giả được
   thông báo chuyển tiền cho tiệm đó.

### Ổn định của hợp đồng

- `v: 1` sẽ không đổi ý nghĩa của 6 trường trên. Thêm trường mới thì vẫn là `v: 1` (vì luật 3 bắt
  app bỏ qua trường lạ). Chỉ khi **đổi hoặc bỏ** một trường đang có mới tăng lên `v: 2`.
- `pattern` **có thể đổi** giữa các bản mapchat. App phải dùng giá trị đọc được từ mã QR, **không**
  chép cứng `MC[A-HJ-NP-Z2-9]{6}` vào code.

Bấm **"Đổi mã"** ở máy quầy thì mã ghép cũ bị xóa khỏi mapchat và khóa được sinh lại — điện thoại
cũ ngừng gửi được ngay lập tức. Quét cùng một mã QR trên điện thoại thứ hai là có hai máy cùng gửi
về; máy quầy tự bỏ bản trùng.

## Lọc ở điện thoại

App nhận được **mọi** thông báo trên máy. Chỉ gửi đi những cái qua được cả hai cửa:

1. **Đúng package của app ngân hàng** mà chủ quán chọn trong danh sách (`com.mbmobile`,
   `com.VCB`, …). Cửa này bắt buộc — không có nó thì bất kỳ app nào cũng dựng được một thông báo
   giả có mã đơn và làm đơn tự xác nhận.
2. **Nội dung khớp `pattern`** — tức là có mã đơn mapchat dạng `MC` + 6 ký tự
   (`MC[A-HJ-NP-Z2-9]{6}`; bảng ký tự cố ý bỏ I, O, 0, 1 để không đọc nhầm). So khớp trên chuỗi đã
   **viết hoa và bỏ hết ký tự không phải chữ/số**, vì ngân hàng hay cắt dòng và chèn dấu cách.

Cửa (2) khiến giao dịch cá nhân của chủ quán không rời khỏi điện thoại: chỉ thông báo có mã đơn
mapchat mới được gửi.

> `pattern` là **mẫu nhận dạng, không phải cơ chế bảo mật** — ai cũng đoán được hình dạng này.
> Việc chặn giả mạo là của cửa (1) và của khóa `k`.

## Một điện thoại, nhiều tiệm

Chủ quán có hai tiệm thì quét hai mã QR. Mỗi tiệm có **mã ghép riêng và khóa riêng** — nhưng
`pattern` giống hệt nhau, và mã đơn không mang dấu vết tiệm nào. Nên khi một thông báo khớp
`pattern`, app **không có cách nào biết** nó thuộc tiệm nào.

**Luật: gửi tới TẤT CẢ các cặp đã ghép** — mỗi cặp một yêu cầu riêng, mã hóa bằng đúng khóa của
cặp đó. Chỉ máy của tiệm đang có đơn mang mã ấy mới xác nhận; các máy còn lại giải mã ra, không
thấy đơn nào khớp, rồi bỏ qua.

Hai tiệm thì mỗi thông báo thành hai yêu cầu HTTP. Hạn mức 60 gói/phút/mã ghép đếm **riêng từng
cặp**, nên không ảnh hưởng.

**Vì sao không lọc theo số tài khoản cho đỡ tốn.** Mỗi ngân hàng viết số tài khoản một kiểu — che
bớt (`TK ...1234`), cắt ngắn, đặt ở vị trí khác nhau, có khi không in số nào. Lọc bằng một chuỗi
mong manh như vậy thì khi sai, biểu hiện là **im lặng bỏ sót một lần thu tiền**. Gửi thừa một yêu
cầu HTTP rẻ hơn nhiều.

## Gói tin

### Bản rõ (trước khi mã hóa)

```json
{ "pkg": "com.mbmobile", "at": 1790000000000, "text": "TK 0123... +150,000VND luc 14:02 ND: MCK7P2QX 8899" }
```

- `pkg` — package đã phát thông báo. Máy quầy ghi vào lịch sử đơn để đối chiếu sau.
- `at` — `System.currentTimeMillis()` lúc nhận thông báo. Dùng để chống đếm trùng.
- `text` — nối `title` + `text` + `bigText` (những cái có), phân cách bằng dấu cách.

### Mã hóa

AES-256-GCM. IV **12 byte ngẫu nhiên cho mỗi gói tin** (không bao giờ dùng lại IV với cùng khóa).
Nhãn xác thực 16 byte nằm **cuối** ciphertext — đây là mặc định của cả `javax.crypto` trên Android
lẫn `createDecipheriv` của Node, nên hai bên khớp nhau mà không phải làm gì thêm.

```kotlin
val cipher = Cipher.getInstance("AES/GCM/NoPadding")
val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
val ct = cipher.doFinal(plaintextJson.toByteArray(Charsets.UTF_8))  // đã gồm nhãn ở cuối
```

Vì GCM là mã hóa có xác thực, **không cần thêm HMAC riêng**: sai khóa, sửa một byte hay phát lại
gói tin của tiệm khác đều bị máy quầy loại ở bước kiểm nhãn.

### Gửi lên

```
POST {url}
Content-Type: application/json

{ "id": "<mã ghép>", "iv": "<base64url>", "ct": "<base64url>" }
```

`base64url` **không đệm** (`Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP` trên Android).
Toàn bộ thân yêu cầu tối đa **2 KB** (`MAX_NOTIFY_ENVELOPE`); thông báo ngân hàng thật cỡ 200 byte,
nên gói nào vượt là bất thường. Nếu thông báo dài quá 2 KB thì **cắt bớt `text` ở phía điện thoại**
rồi hãy gửi; mã đơn và số tiền luôn nằm ở phần đầu.

### Trả lời — và app phải làm gì

| mã | thân | app làm gì |
|---|---|---|
| 200 | `{"ok":true}` | xong, xóa khỏi hàng đợi |
| 503 | `{"ok":false,"error":"store_offline"}` | máy quầy đang tắt — **giữ lại, thử lại sau** |
| 429 | `{"ok":false,"error":"rate_limited"}` | gửi quá nhanh — lùi lại rồi thử lại |
| 404 | `{"ok":false,"error":"unknown_pairing"}` | mã ghép đã bị đổi — **ngừng thử lại**, báo chủ quán quét lại QR |
| 400 / 413 | `bad_request` / `too_large` | gói tin sai — bỏ, không thử lại |

Chỉ 503 và 429 (và lỗi mạng) mới đáng thử lại. Đề xuất: lùi dần 5s → 15s → 60s → 5 phút, giữ tối
đa vài giờ; quá hạn thì bỏ, vì nhân viên đã xác nhận tay từ lâu rồi.

Giới hạn hiện tại: **60 gói tin / phút / mã ghép**, và **300 / phút / địa chỉ IP**.

## Rủi ro phía điện thoại

- **Mã QR chứa khóa, đừng chụp màn hình gửi đi.** Ai có ảnh mã QR là đọc và giả được thông báo —
  cách duy nhất là bấm **"Đổi mã"** ở máy quầy.
- **Thông báo giả nếu bật nhầm package**: OpenNotify nên chỉ cho chọn package từ danh sách app ngân
  hàng đã biết, và nói rõ hậu quả nếu chọn bừa.

---

## OpenNotify làm cụ thể thế nào

Một cặp ghép là một **webhook loại mapchat** (`WebhookKind.MAPCHAT`) gắn với **một** app đang theo
dõi, tức là cửa (1) chính là app mà người dùng đặt webhook vào. Webhook thường (HMAC, gửi JSON rõ
cho backend riêng) giữ nguyên cách cũ.

| Luật | Ở đâu |
|---|---|
| Quét QR, ép UTF-8 khi giải | `WebhookEditActivity.scanPairing()` — ZXing embedded với `Intents.Scan.CHARACTER_SET = "UTF-8"`; thêm `PairingQr.repairMojibake()` phòng khi máy vẫn giải sai |
| Luật đọc 2–6 | `PairingQr.parse()` |
| Luật 7: quét lại cùng `id` = cập nhật | `Db.findPairing(pkg, id)` — trong **cùng một app ngân hàng**; ghép cùng tiệm với app ngân hàng khác là một cặp riêng của app đó |
| Luật 8: không hiện/log `k` | màn sửa webhook không có ô khóa cho loại mapchat; không có dòng log nào; `allowBackup="false"` |
| Lọc trên chuỗi viết hoa, bỏ ký tự không phải chữ/số | `PatternMatcher.inputFor()` — chỉ xét title + text + bigText, đúng phần được gửi đi làm `text` |
| Nhiều tiệm → gửi tới tất cả | mỗi cặp là một webhook; mọi webhook khớp đều được gửi |
| Bản rõ, mã hóa, trần 2 KB | `MapchatEnvelope.seal()` |
| Xử lý mã trả lời | `Sender.classify()`: 2xx xong · 404 đánh dấu cặp "mã ghép đã bị đổi" và ngừng gửi tới khi quét lại · 503/429/lỗi mạng thử lại · mã khác bỏ |
| Lùi dần & quá hạn | gửi ngay thử 3 lần (0s → 5s → 15s), sau đó `OutboxWorker` thử lại mỗi ~15 phút (WorkManager không cho chu kỳ ngắn hơn); quá **6 giờ** thì bỏ. Khi đọc bù lúc kết nối lại, thông báo đã nằm trên máy quá 6 giờ không được gửi cho cặp mapchat |
| Nút "Gửi sự kiện test" | gửi một gói thật đã mã hóa, nội dung không có mã đơn: máy quầy giải mã được rồi bỏ qua. Kết quả hiện theo mã trả lời (thông suốt / máy quầy tắt / mã ghép đã đổi / không tới được mạng) |

Trạng thái lượt gửi (`deliveries.sent`): `0` chờ gửi, `1` đã gửi, `2` đã bỏ (không thử lại).
