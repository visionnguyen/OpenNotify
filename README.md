# OpenNotify

App Android nội bộ: **chỉ** ghi lại thông báo của những ứng dụng bạn chủ
động thêm vào — kể cả khi màn hình tắt — để duyệt lại trong app. Với mỗi
ứng dụng bạn cấu hình một hoặc nhiều **webhook**, mỗi webhook có URL,
secret và bộ **pattern regex** riêng (kết hợp bằng VÀ / HOẶC). Thông báo
khớp webhook nào thì nguyên văn nội dung được đẩy lên webhook đó, kèm chữ
ký HMAC-SHA256. Việc parse số tiền / mã đơn hàng để nguyên cho backend xử
lý, vì sửa regex ở backend không cần build lại APK.

**Phạm vi:** công cụ dùng nội bộ, một thiết bị, không nhằm mục đích scale
cho nhiều cửa hàng/khách hàng. Không thay thế API chính thức của ngân
hàng cho mục đích thương mại.

**Lưu ý về quyền riêng tư:** Android không cho giới hạn quyền đọc thông
báo theo từng ứng dụng, nên OpenNotify về mặt kỹ thuật *thấy* mọi thông
báo — nhưng nó bỏ qua ngay mọi ứng dụng chưa được thêm vào danh sách.
Thông báo của ứng dụng đã thêm nằm trong SQLite local dưới dạng **chưa mã
hóa**; chỉ phần khớp webhook mới rời khỏi máy.

## Vì sao dùng NotificationListenerService thay vì poll bằng script

Khi màn hình tắt, Android áp Doze / App Standby / (từ Android 14) cached
app freezer lên các tiến trình nền thông thường — script chạy trong
Termux nằm đúng diện này. `NotificationListenerService` thì khác: hệ
thống tự bind và **đánh thức** service ngay khi có thông báo mới, nên nó
chịu các giới hạn trên tốt hơn hẳn một script poll.

## Cách dùng trong app

- **Trang chủ** mặc định **trống** — chưa ghi nhận thông báo nào. Bấm dấu
  **+** để mở danh sách toàn bộ ứng dụng trên máy (icon + tên), tìm kiếm,
  chọn một hoặc nhiều ứng dụng rồi bấm **Thêm**. Từ lúc này OpenNotify
  mới lấy thông báo của các ứng dụng đó.
- Trang chủ hiển thị các ứng dụng đang theo dõi, mỗi ứng dụng kèm **số
  thông báo chưa đọc**. Bấm vào ứng dụng để xem chi tiết các thông báo
  (mở ra là đánh dấu đã đọc; thông báo mới in đậm).
- Trong màn chi tiết thông báo: **vuốt một dòng sang trái** để lộ nút **Xóa**
  ở cuối dòng; nút **thùng rác** trên thanh công cụ xóa hết thông báo của
  ứng dụng đó (có hỏi xác nhận). Xóa là ẩn khỏi danh sách — bản gốc vẫn giữ
  tới khi dọn định kỳ (30 ngày) để webhook chưa gửi được vẫn gửi bù và thông
  báo bị app nguồn đăng lại không bị ghi/gửi trùng.
- Nút **Cài đặt** (biểu tượng bánh răng, góc phải trang chủ): xem lại và
  bật/tắt mọi thứ liên quan — quyền đọc thông báo, bỏ giới hạn pin, hiện thông
  báo “đang chạy”, âm thanh khi có thông báo (bật/tắt + chọn âm thanh hệ
  thống, mặc định là âm thông báo mặc định của máy). Các quyền hệ thống app
  không tự bật/tắt được: bấm vào hàng sẽ mở đúng màn hình hệ thống, công tắc
  phản ánh trạng thái thật.
- Bên dưới phần cài đặt là **biểu đồ uptime 24h/ngày** (7 ngày gần nhất, mỗi
  ngày một thanh 96 ô × 15 phút, kèm % uptime) để quan sát Android có thực sự
  giao thông báo cho app hay đang giới hạn nó — xem mục “Biểu đồ uptime”.
- Nút **cấu hình** (biểu tượng bánh răng) của từng ứng dụng: thêm / sửa /
  bật-tắt / xóa **nhiều webhook**. Mỗi webhook gồm tên, URL, secret, danh
  sách pattern regex và cách kết hợp:
  - **HOẶC**: khớp một trong các pattern là gửi;
  - **VÀ**: phải khớp tất cả pattern;
  - không có pattern nào: nhận mọi thông báo của ứng dụng.
- Menu **⋮ → Bỏ theo dõi ứng dụng này** (trong màn cấu hình) xóa ứng dụng
  khỏi danh sách cùng thông báo và webhook của nó.

## Kiến trúc

```
Bất kỳ app nào đăng thông báo trên máy
        │
        ▼
NotifyListenerService.onNotificationPosted()   ← hệ thống tự gọi, kể cả khi tắt màn hình
        │  package có trong bảng "tracked_apps"?
        ├─ không → bỏ qua hoàn toàn
        │
        │  có → trích xuất title/text/bigText/subText/lines (thô, chưa parse)
        │       và lưu vào bảng "notifications" (Db.kt), is_read = 0
        ▼
  với từng webhook ĐANG BẬT của ứng dụng đó: bộ pattern có khớp không?
  (PatternMatcher: rỗng = khớp; HOẶC = any; VÀ = all)
        │
        ├─ không khớp → chỉ nằm trong log để duyệt
        │
        └─ khớp       → ghi dòng "deliveries" (sent=0), HTTP POST + HMAC-SHA256
                        tới URL/secret của chính webhook đó, retry với backoff
                               │
                               ▼ (nếu thất bại)
                     OutboxWorker (WorkManager, mỗi 15 phút) gửi lại các
                     deliveries sent=0
```

`KeepAliveService` là một foreground service gần như "rỗng" — chỉ giữ
tiến trình app không bị hệ thống đóng băng trên các ROM tối ưu pin mạnh
(MIUI, ColorOS, FuntouchOS...). Việc đọc thông báo thật sự vẫn do
`NotifyListenerService` đảm nhiệm.

## Payload gửi tới webhook (chỉ với thông báo khớp luật)

```json
{
  "source": "opennotify",
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
ra, nội dung có dấu, số lớn) qua màn hình chi tiết của từng ứng dụng trong
app, rồi mới viết pattern regex và logic parse ở backend theo đúng mẫu
đó — mình chưa có mẫu thật từ MBBank hay các nguồn khác nên không đảm
bảo chính xác 100% cấu trúc nội dung.

## Build APK

Không cần Android Studio. Repo có sẵn GitHub Actions workflow tự build.

1. Đẩy repo này lên một GitHub repo (public hoặc private đều được).
2. Vào tab **Actions** → chọn workflow **Build APK** → **Run workflow**
   (hoặc chỉ cần push lên nhánh `main`, workflow tự chạy).
3. Sau khi chạy xong, mở run đó → phần **Artifacts** → tải
   `opennotify-debug-apk` (file zip chứa `app-debug.apk`).
4. Copy file `.apk` vào điện thoại (qua `adb push`, Google Drive, Termux,
   USB...) rồi cài đặt. Vì đây là APK debug tự ký (không qua Play Store),
   cần bật "Cài đặt từ nguồn không xác định" cho app dùng để mở file APK.

Nếu bạn có Android SDK cài sẵn, có thể build local bằng
`gradle assembleDebug` sau khi tạo file `local.properties` trỏ `sdk.dir`
tới đường dẫn SDK.

## Cài đặt sau khi cài APK

1. Mở app **OpenNotify**. Nếu thấy banner "Chưa cấp quyền đọc thông
   báo", bấm **Cấp quyền** → bật **OpenNotify** trong danh sách.
2. Bấm biểu tượng **Cài đặt** (bánh răng) → bật **Bỏ giới hạn pin** → xác
   nhận cho phép chạy nền không giới hạn. Cũng ở đây có thể kiểm tra lại
   quyền đọc thông báo và chọn âm thanh báo.
3. Bấm **+**, chọn các ứng dụng cần lấy thông báo (ví dụ MBBank) → **Thêm**.
4. Bấm nút cấu hình của ứng dụng → **+ Thêm webhook**: điền URL, secret
   (đặt giống hệt ở backend), pattern nếu cần → **Gửi sự kiện test tới
   webhook** để xác nhận backend nhận được và verify chữ ký đúng → **Lưu**.
5. Dùng máy bình thường một lúc, mở ứng dụng trong OpenNotify để xem mẫu
   thông báo thật rồi chỉnh pattern cho khớp.

### Cài đặt riêng theo hãng máy (bắt buộc trên nhiều ROM Android)

Ngoài 2 bước trên, các ROM tùy biến mạnh thường có thêm lớp giới hạn
riêng, cần bật thủ công trong Cài đặt hệ thống (không phải trong app):

- **Xiaomi (MIUI/HyperOS):** Cài đặt ứng dụng → OpenNotify và app nguồn
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

## Biểu đồ uptime

Mục đích: biết app có *thực sự* lấy được thông báo hay bị Android giới hạn, theo
kiểu chỉ số uptime 99.9% của server. Đo dựa trên chính kết nối mà Android cấp cho
`NotificationListenerService`:

- Mỗi lần hệ thống bind listener (`onListenerConnected`) mở một **phiên**, gắn
  với tiến trình đang chạy; phiên đóng khi bị unbind (`onListenerDisconnected` /
  `onDestroy`) hoặc khi nhịp tim mỗi phút thấy listener không còn liên lạc được
  với hệ thống. Mỗi nhịp tim và mỗi thông báo tới (của bất kỳ app nào) cập nhật
  “lần cuối thấy sống”.
- Trong một phiên = **hoạt động**, kể cả lúc máy ngủ sâu làm nhịp tim ngưng (tiến
  trình còn, hệ thống sẽ đánh thức khi có thông báo).
- Tiến trình bị Android giết không kịp báo → tính là **mất** từ lần cuối thấy sống
  tới khi được bind lại. Tắt máy cũng tính là mất. Trước lúc bắt đầu theo dõi là
  “chưa có dữ liệu”, không tính vào %.

Giới hạn cần biết: đây là bằng chứng gián tiếp (Android báo đã bind), không phải
bằng chứng từng thông báo một đều được giao. Nếu muốn chắc hơn nữa, vẫn nên dùng
cách kiểm chứng độ trễ ở mục dưới.

## Cách kiểm chứng độ trễ khi tắt màn hình

1. Tắt màn hình, để máy yên khoảng 30-60 phút (hoặc dùng `adb shell
   dumpsys deviceidle force-idle` để ép vào Doze ngay nếu bạn nối máy
   qua Wireless debugging).
2. Kích hoạt một thông báo thật từ ứng dụng đã thêm và cấu hình webhook (ví dụ
   chuyển một khoản tiền nhỏ vào tài khoản MBBank đã chia sẻ biến động).
3. So sánh `post_time` trong payload nhận ở backend với thời điểm thực
   tế.
4. Lặp lại vài lần ở các khung giờ khác nhau để chắc độ trễ ổn định.

Nếu vẫn thấy trễ hoặc mất thông báo dù đã làm đủ các bước cài đặt trên,
khả năng cao nằm ở việc app nguồn (MBBank...) bị OEM hạn chế chạy nền —
kiểm tra lại các mục pin/tự khởi động cho chính app đó.

## Backend mẫu (Node.js)

Repo này có kèm một backend mẫu để nhận webhook, verify chữ ký, dedupe
và thử parse số tiền/mã đơn — xem [`backend/README.md`](backend/README.md).
Đây là hàng mẫu để tự mở rộng, đã được test end-to-end trong quá trình
viết, không phải service production sẵn dùng.

## Dữ liệu lưu local

- `tracked_apps`: ứng dụng đã thêm (package + tên hiển thị).
- `notifications`: thông báo của các ứng dụng đó, giữ tối đa 30 ngày (tự
  dọn định kỳ trong `OutboxWorker`, chỉnh hằng số `olderThanMs` nếu muốn).
  Cột `is_read` cho số chưa đọc trên trang chủ.
- `webhooks` + `webhook_patterns`: webhook và bộ pattern của từng ứng dụng.
- `deliveries`: trạng thái gửi từng (thông báo, webhook), phục vụ gửi lại.
- `listener_sessions`: các phiên kết nối của listener, nguồn của biểu đồ uptime
  (giữ 14 ngày). Cột `notifications.is_deleted` là cờ xóa mềm.
- Cài đặt âm thanh lưu trong SharedPreferences (`Prefs.kt`).

Nâng cấp từ bản cũ (ghi mọi thông báo + luật relay toàn cục, một webhook
chung) sẽ **reset DB** — cần thêm lại ứng dụng và webhook.

## Cấu trúc repo

```
opennotify/
├── app/
│   ├── build.gradle.kts
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── kotlin/dev/ghien/opennotify/
│       │   ├── NotifyListenerService.kt    # chỉ nhận app đã thêm, khớp webhook, gửi ngay
│       │   ├── Db.kt                       # SQLite: tracked_apps, notifications, webhooks, patterns, deliveries
│       │   ├── PatternMatcher.kt           # khớp bộ pattern theo VÀ / HOẶC
│       │   ├── Payload.kt                  # dựng JSON payload dùng chung
│       │   ├── OutboxWorker.kt             # lưới an toàn, gửi lại định kỳ + dọn log cũ
│       │   ├── KeepAliveService.kt         # foreground service chống bị kill
│       │   ├── BootReceiver.kt             # khởi động lại sau reboot
│       │   ├── Signer.kt                   # HMAC-SHA256 / SHA-1
│       │   ├── WebhookClient.kt            # POST tới webhook
│       │   ├── MainActivity.kt             # trang chủ: app đang theo dõi + số chưa đọc + nút +
│       │   ├── AppPickerActivity.kt        # chọn/tìm ứng dụng trên máy để thêm
│       │   ├── NotificationListActivity.kt # thông báo của một ứng dụng (vuốt để xóa, xóa hết)
│       │   ├── SettingsActivity.kt         # cài đặt bật/tắt + biểu đồ uptime 24h/ngày
│       │   ├── Liveness.kt / UptimeBarView.kt # đo + vẽ uptime của listener
│       │   ├── Prefs.kt / SoundPlayer.kt   # cài đặt âm thanh + phát âm thanh khi có thông báo
│       │   ├── AppConfigActivity.kt        # danh sách webhook của một ứng dụng
│       │   └── WebhookEditActivity.kt      # sửa webhook: URL, secret, pattern, VÀ/HOẶC
│       └── res/...
├── .github/workflows/build-apk.yml    # build APK tự động, không cần Android Studio
├── backend/                            # backend mẫu nhận webhook (Node.js) — xem backend/README.md
│   ├── src/server.js                   # verify HMAC, dedupe, parse, ghi log
│   ├── src/parse.js                    # regex mẫu, CẦN chỉnh theo dữ liệu thật
│   └── test/send-test-event.js         # test không cần điện thoại
└── README.md
```
