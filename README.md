# Backrooms

**Backrooms** là một trò chơi khám phá góc nhìn thứ nhất mang bầu không khí kinh dị, lấy cảm hứng từ vũ trụ Backrooms.

Khám phá những không gian vô tận mang cảm giác siêu thực, tìm đường qua các môi trường được tạo theo thuật toán, phát hiện những bức tường không ổn định và sống sót trước các thực thể nguy hiểm.

## Tính năng

- **Khám phá góc nhìn thứ nhất** – Khám phá những môi trường đậm chất không gian siêu thực với trải nghiệm nhập vai góc nhìn thứ nhất.
- **Môi trường được tạo theo thuật toán** – Mỗi ván chơi mới tạo ra một thế giới hoàn toàn ngẫu nhiên từ một seed riêng. Seed được lưu cùng bản lưu; những người chơi trong cùng một phiên multiplayer sử dụng chung thế giới.
- **Nhiều tầng khác nhau** – Mỗi tầng có môi trường, bố cục và tiến trình riêng.
- **Tường Noclip** – Khám phá những bức tường không ổn định, cho phép di chuyển đến các tầng khác.
- **Nhiều người chơi (Multiplayer)** – Kết nối với máy chủ Multiplayer thông qua địa chỉ IP và cổng kết nối; có các thành phần nền tảng cho máy chủ LAN tự quảng bá, dò tìm world trong cùng mạng và lưu địa chỉ máy chủ.
- **Hệ thống lưu trò chơi** – Lưu tiến trình, tải lại trò chơi và quản lý các ô lưu.
- **Tùy chỉnh cài đặt** – Điều chỉnh góc nhìn, sương mù và độ khó.
- **Tối ưu hóa kết xuất hình ảnh** – Sử dụng bộ nhớ đệm theo từng khu vực và tối ưu hóa quá trình kết xuất để cải thiện hiệu năng.
- **Điều khiển trên thiết bị di động** – Hỗ trợ joystick cảm ứng, điều khiển camera và chuyển đổi giữa đi bộ/chạy.

## Gameplay

### Điều khiển

| Điều khiển | Chức năng |
|---|---|
| Nửa bên trái màn hình | Joystick di chuyển |
| Nửa bên phải màn hình | Quan sát xung quanh |
| Nút RUN | Chuyển đổi giữa đi bộ/chạy |
| Chính giữa phía trên | Tạm dừng / Cài đặt |
| Góc trên bên phải | Bật / tắt bản đồ thu nhỏ |
| Nút Back | Tạm dừng / Điều hướng menu |

### Cài đặt

- **Field of View (FOV):** Có thể điều chỉnh từ 30° đến 110°.
- **Fog:** Có thể tùy chỉnh cường độ sương mù.
- **Difficulty:** Bao gồm Easy, Normal, Hard và Nightmare.

## Multiplayer

Kết nối trực tiếp hiện dùng địa chỉ `host` hoặc `host:port`; nếu bỏ cổng, game dùng cổng mặc định 7777. Lớp dùng chung `LanMultiplayer.kt` cung cấp API để một giao diện tạo máy chủ LAN với cổng ngẫu nhiên (`LanHost(0)`) hoặc cổng tự chọn, quét các máy chủ đang quảng bá trên mạng (`discoverLanServers`) và duy trì danh sách máy chủ lưu (`LanServerBook`). Việc quét LAN cần được gọi trên luồng nền. Bộ định tuyến có thể chặn broadcast; khi đó vẫn có thể kết nối bằng địa chỉ thủ công.

Máy chủ dòng lệnh trên máy tính vẫn chạy bằng `java -jar backrooms.jar server [port]`.

## Biên dịch dự án

### Yêu cầu

- Android Studio hoặc Android SDK.
- JDK 17.
- Android SDK Platform 34.
- Gradle 8.x.

### Biên dịch APK Debug

```bash
gradle assembleDebug
```

### Biên dịch APK Release

```bash
gradle assembleRelease
```

Các tệp APK sau khi biên dịch sẽ nằm tại:

```text
app/build/outputs/apk/
```

### Ký APK Release

Cấu hình ký APK Release sử dụng các biến môi trường:

| Biến | Mô tả |
|---|---|
| `KEYSTORE_PATH` | Đường dẫn đến tệp keystore |
| `KEYSTORE_PASSWORD` | Mật khẩu keystore |
| `KEY_ALIAS` | Bí danh của khóa ký |
| `KEY_PASSWORD` | Mật khẩu khóa ký |

Nếu không cung cấp thông tin ký, bản Release vẫn có thể được biên dịch nhưng sẽ không sử dụng cấu hình ký tùy chỉnh.

## Thông tin kỹ thuật

| Thành phần | Cấu hình |
|---|---|
| Ngôn ngữ lập trình | Kotlin |
| Nền tảng | Android và desktop JVM |
| Application ID | `com.backrooms.game` |
| Android SDK tối thiểu | 21 |
| Compile SDK | 34 |
| Target SDK | 34 |
| Kotlin | 2.0.21 |
| Android Gradle Plugin | 8.5.2 |
| JVM Target | 17 |

## Cấu trúc dự án

```text
Backroom/
├── app/
│   └── src/main/java/
│       ├── MainActivity.kt
│       └── core/
├── desktop/
├── build.gradle.kts
├── settings.gradle.kts
└── gradle.properties
```

## Hiệu năng

Trò chơi tích hợp nhiều phương pháp tối ưu hóa kết xuất hình ảnh:

- Lưu bộ nhớ đệm dữ liệu mê cung bằng các khu vực bất biến kích thước 32×32.
- Giảm thiểu các phép tính tạo thế giới bị lặp lại.
- Tối ưu hóa quy trình kết xuất hình ảnh.
- Hỗ trợ kết xuất Canvas bằng tăng tốc phần cứng trên các phiên bản Android tương thích.

## Bộ kết xuất

Khung hình mặc định ở 1080p (1920×1080). Có thể đổi trong Options: 270p, 360p, 540p, 720p hoặc 1080p (luôn là 16:9). Trên Android ở mục GRAPHICS → FRAME SIZE, trên desktop trong Options.

**Android** (Options → GRAPHICS → RENDERER, mặc định là Canvas):

- **Canvas**: CPU vẽ toàn bộ khung hình rồi Android hiển thị. Hoạt động trên mọi máy.
- **OpenGL ES** (cần ES 3.0) và **Vulkan**: GPU tự vẽ hình 3D; nếu backend được chọn không khởi động được, trò chơi tự quay về Canvas.

Ba level thế giới mở (City, Island, Graveyard) hiện vẫn được vẽ bằng CPU.

**Desktop** (Linux + Mesa): `java -jar backrooms.jar --zink` chạy pipeline OpenGL của Java2D trên Zink (OpenGL chạy trên Vulkan).

## Links & Social

- Discord: https://discord.gg/DfpyAs9TaZ

## Giấy phép, danh đề và ghi công

### Giấy phép

Mã nguồn của **Backrooms** được phát hành theo giấy phép **GNU General Public License v3.0 (GPL-3.0-only)**.

Các nội dung bên thứ ba được sử dụng trong dự án, bao gồm cấp độ, hình ảnh, âm thanh, tài liệu và các tài nguyên khác, có thể được áp dụng giấy phép riêng. Vui lòng xem [THIRD_PARTY_LICENSES.md](THIRD_PARTY_LICENSES.md).

### Danh đề và ghi công

Backrooms xin ghi nhận công sức sáng tạo của cộng đồng Backrooms, đặc biệt là những tác giả đã đóng góp các cấp độ, ý tưởng, hình ảnh và nội dung được sử dụng trong dự án.

### Tuyên bố miễn trừ

**Backrooms** là dự án do cộng đồng phát triển, lấy cảm hứng từ vũ trụ Backrooms. Mọi quyền đối với nội dung của bên thứ ba vẫn thuộc về chủ sở hữu tương ứng.
