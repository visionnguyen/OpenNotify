# MB Relay

App Android nội bộ: ghi lại **toàn bộ thông báo trên máy** (mặc định,
không lọc theo nguồn) ngay khi chúng xuất hiện — kể cả khi màn hình tắt
— để bạn duyệt lại trong app. Với những thông báo khớp một **luật relay**
bạn tự định nghĩa (theo package + pattern regex tùy chọn), app đẩy nguyên
văn nội dung lên một webhook do bạn tự host, kèm chữ ký HMAC-SHA256 để
xác thực. Việc parse số tiền / mã đơn hàng để nguyên cho backend xử lý,
vì sửa regex ở backend không cần build lại APK.

**Phạm vi:** công cụ dùng nội bộ, một thiết bị, không nhằm mục đích scale
cho nhiều cửa hàng/khách hàng. Không thay thế API chính thức của ngân
hàng cho mục đích thương mại.

**Lưu ý về quyền riêng tư:** vì bắt toàn bộ thông báo trên máy (tin nhắn,
mạng xã hội, email...), nội dung đó nằm trong SQLite local của app dưới
dạng **chưa mã hóa**. Chỉ phần khớp luật relay mới rời khỏi máy.

## Vì sao dùng NotificationListenerService thay vì poll bằng script

Khi màn hình tắt, Android áp Doze / App Standby / (từ Android 14) cached
app freezer lên các tiến trình nền thông thường — script chạy trong
Termux nằm đúng diện này. `NotificationListenerService` thì khác: hệ
thống tự bind và **đánh thức** service ngay khi có thông báo mới, nên nó
chịu các giới hạn trên tốt hơn hẳn một script poll.

## Kiến trúc

```
Bất kỳ app nào đăng thông báo trên máy
        │
        ▼
MbListenerService.onNotificationPosted()   ← hệ thống tự gọi, kể cả khi tắt màn hình
        │  trích xuất title/text/bigText/subText/lines (thô, chưa parse)
        │  LUÔN lưu vào bảng "notifications" (Db.kt) — không mất dữ liệu nếu app bị kill
        │
        ▼
  kiểm tra bảng "relay_rules": có rule nào đang bật, đúng package,
  và (pattern rỗng HOẶC regex khớp nội dung) không?
        │
        ├─ không khớp  → dừng lại, chỉ nằm trong log để duyệt sau
        │
        └─ khớp        → gửi HTTP POST + HMAC-SHA256 tới webhook, retry với backoff
                               │
                               ▼ (nếu thất bại)
                     OutboxWorker (WorkManager, mỗi 15 phút) gửi lại các
                     thông báo relay_matched=1, relay_sent=0
```

`KeepAliveService` là một foreground service gần như "rỗng" — chỉ giữ
tiến trình app không bị hệ thống đóng băng trên các ROM tối ưu pin mạnh
(MIUI, ColorOS, FuntouchOS...). Việc đọc thông báo thật sự vẫn do
`MbListenerService` đảm nhiệm.

## Duyệt lại thông báo trong app

- **Xem nguồn thông báo đã ghi nhận** (từ MainActivity) → liệt kê mọi
  package từng gửi thông báo, kèm số lượng và lần gần nhất.
- Bấm vào một nguồn → xem danh sách thông báo của package đó, mỗi dòng
  gắn nhãn `[đã relay]` / `[chờ relay]` nếu khớp luật.
- Bấm vào một thông báo → xem đầy đủ title/text/bigText/subText/lines,
  và có thể **tạo luật relay ngay từ đó** (package được điền sẵn).
- **Quản lý luật relay** (từ MainActivity) → thêm/xóa/bật-tắt luật. Một
  luật gồm package (bắt buộc) + pattern regex (tùy chọn, để trống = khớp
  mọi thông báo từ package đó). Regex được validate trước khi lưu.

## Payload gửi tới webhook (chỉ với thông báo khớp luật)

```json
{
  "source": "mb-relay",
  "package": "com.mbmobile",
  "key": "...",
  "post_time": 1730000000000,
  "title": "...",
  "text": "...",
  "big_text": "...",
  "sub_text": "...",
  "lines": "..."
}
```

Header `X-Signature` = `hex(HMAC_SHA256(secret, raw_body))`. Backend xác
thực bằng cách tính lại HMAC trên đúng raw body nhận được và so sánh
(dùng so sánh constant-time, ví dụ `hmac.compare_digest` trong Python).

**Lưu ý quan trọng:** trước khi tin vào field nào, hãy tự thu thập vài
mẫu thông báo thật của từng nguồn bạn quan tâm (giao dịch vào, giao dịch
ra, nội dung có dấu, số lớn) qua màn hình "Xem nguồn thông báo" trong
app, rồi mới viết pattern regex và logic parse ở backend theo đúng mẫu
đó — mình chưa có mẫu thật từ MBBank hay các nguồn khác nên không đảm
bảo chính xác 100% cấu trúc nội dung.

## Build APK

Không cần Android Studio. Repo có sẵn GitHub Actions workflow tự build.

1. Đẩy repo này lên một GitHub repo (public hoặc private đều được).
2. Vào tab **Actions** → chọn workflow **Build APK** → **Run workflow**
   (hoặc chỉ cần push lên nhánh `main`, workflow tự chạy).
3. Sau khi chạy xong, mở run đó → phần **Artifacts** → tải
   `mb-relay-debug-apk` (file zip chứa `app-debug.apk`).
4. Copy file `.apk` vào điện thoại (qua `adb push`, Google Drive, Termux,
   USB...) rồi cài đặt. Vì đây là APK debug tự ký (không qua Play Store),
   cần bật "Cài đặt từ nguồn không xác định" cho app dùng để mở file APK.

Nếu bạn có Android SDK cài sẵn, có thể build local bằng
`gradle assembleDebug` sau khi tạo file `local.properties` trỏ `sdk.dir`
tới đường dẫn SDK.

## Cài đặt sau khi cài APK

1. Mở app **MB Relay**.
2. Điền **Webhook URL** (endpoint backend của bạn) và **Webhook Secret**
   (chuỗi bí mật dùng để ký HMAC — đặt giống hệt ở backend). Bấm
   **Lưu cấu hình**.
3. Bấm **"1. Cấp quyền đọc thông báo"** → tìm **MB Relay** trong danh
   sách → bật quyền. Quyền này cho phép app thấy MỌI thông báo trên máy
   — không có cách giới hạn ở cấp hệ điều hành, việc lọc hoàn toàn nằm ở
   bảng luật relay trong app.
4. Bấm **"2. Bỏ giới hạn pin cho app này"** → xác nhận cho phép chạy nền
   không giới hạn.
5. Bấm **"Gửi sự kiện test tới webhook"** để xác nhận backend nhận được
   và verify chữ ký đúng, trước khi phụ thuộc vào giao dịch thật.
6. Dùng máy bình thường một lúc, vào **"Xem nguồn thông báo đã ghi
   nhận"** để xem các package đang gửi thông báo, xác định đúng package
   bạn cần (ví dụ `com.mbmobile`), rồi vào **"Quản lý luật relay"** để
   thêm luật cho đúng nguồn + pattern mong muốn.

### Cài đặt riêng theo hãng máy (bắt buộc trên nhiều ROM Android)

Ngoài 2 bước trên, các ROM tùy biến mạnh thường có thêm lớp giới hạn
riêng, cần bật thủ công trong Cài đặt hệ thống (không phải trong app):

- **Xiaomi (MIUI/HyperOS):** Cài đặt ứng dụng → MB Relay và app nguồn
  (ví dụ MBBank) → bật **Tự khởi chạy**; bật **Không giới hạn** ở phần
  tiết kiệm pin; khóa cả hai app trong màn hình multitask.
- **Oppo/OnePlus (ColorOS):** Cài đặt pin → Quản lý pin ứng dụng → đặt
  cả hai app ở **Cho phép nền hoạt động**; khóa app trong recent apps.
- **Vivo (FuntouchOS/OriginOS):** i-manager → Quản lý ứng dụng → cho
  phép **Tự khởi động cao** và chạy nền cho cả hai app.
- **Samsung:** Cài đặt pin → thêm cả hai app vào **Không tối ưu hóa** /
  loại khỏi **Ngủ đông ứng dụng**; khóa app trong recent apps.

Nếu bỏ qua bước này, listener và app nguồn vẫn có thể bị hệ thống giết
sau vài giờ dù đã cấp quyền trong app.

## Cách kiểm chứng độ trễ khi tắt màn hình

1. Tắt màn hình, để máy yên khoảng 30-60 phút (hoặc dùng `adb shell
   dumpsys deviceidle force-idle` để ép vào Doze ngay nếu bạn nối máy
   qua Wireless debugging).
2. Kích hoạt một thông báo thật từ nguồn đã tạo luật relay (ví dụ
   chuyển một khoản tiền nhỏ vào tài khoản MBBank đã chia sẻ biến động).
3. So sánh `post_time` trong payload nhận ở backend với thời điểm thực
   tế.
4. Lặp lại vài lần ở các khung giờ khác nhau để chắc độ trễ ổn định.

Nếu vẫn thấy trễ hoặc mất thông báo dù đã làm đủ các bước cài đặt trên,
khả năng cao nằm ở việc app nguồn (MBBank...) bị OEM hạn chế chạy nền —
kiểm tra lại các mục pin/tự khởi động cho chính app đó.

## Gợi ý backend nhận webhook (không nằm trong repo này)

- Xác thực `X-Signature` bằng HMAC-SHA256 trên raw body, so sánh
  constant-time.
- Dùng `key` + `post_time` (hoặc hash tương tự phía app) làm khóa
  idempotency — MB Relay có thể gửi lại cùng một thông báo nếu chưa
  nhận được phản hồi 2xx kịp lúc.
- Parse số tiền / mã đơn hàng từ `text`/`big_text`/`lines` bằng regex
  điều chỉnh theo mẫu thật thu thập được từ từng nguồn.

## Dữ liệu lưu local

- Bảng `notifications`: mọi thông báo bắt được, giữ tối đa 30 ngày
  (tự dọn định kỳ trong `OutboxWorker`, chỉnh hằng số `olderThanMs` nếu
  muốn giữ lâu/ngắn hơn).
- Bảng `relay_rules`: danh sách luật package + pattern do bạn tạo qua
  màn hình "Quản lý luật relay".

## Cấu trúc repo

```
mb-relay/
├── app/
│   ├── build.gradle.kts
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── kotlin/dev/ghien/mbrelay/
│       │   ├── MbListenerService.kt        # bắt mọi thông báo, khớp luật, gửi ngay nếu khớp
│       │   ├── Db.kt                       # SQLite: bảng notifications + relay_rules
│       │   ├── Payload.kt                  # dựng JSON payload dùng chung
│       │   ├── OutboxWorker.kt             # lưới an toàn, gửi lại định kỳ + dọn log cũ
│       │   ├── KeepAliveService.kt         # foreground service chống bị kill
│       │   ├── BootReceiver.kt             # khởi động lại sau reboot
│       │   ├── Signer.kt                   # HMAC-SHA256 / SHA-1
│       │   ├── WebhookClient.kt            # POST tới webhook
│       │   ├── Prefs.kt                    # cấu hình webhook URL/secret
│       │   ├── MainActivity.kt             # cấu hình + test + điều hướng
│       │   ├── SourcesActivity.kt          # liệt kê nguồn đã ghi nhận
│       │   ├── NotificationListActivity.kt # danh sách + chi tiết thông báo theo nguồn
│       │   └── RulesActivity.kt            # thêm/xóa/bật-tắt luật relay
│       └── res/...
├── .github/workflows/build-apk.yml    # build APK tự động, không cần Android Studio
└── README.md
```
