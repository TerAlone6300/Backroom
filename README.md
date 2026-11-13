# Backrooms

**Backrooms** là một trò chơi khám phá góc nhìn thứ nhất mang bầu không khí kinh dị, lấy cảm hứng từ vũ trụ Backrooms.

Khám phá những không gian vô tận mang cảm giác siêu thực, tìm đường qua các môi trường được tạo theo thuật toán, phát hiện những bức tường không ổn định và tìm lối thoát qua các tầng khác nhau.

## Tính năng

- **Khám phá góc nhìn thứ nhất** – Khám phá những môi trường đậm chất không gian siêu thực với trải nghiệm nhập vai góc nhìn thứ nhất.
- **Môi trường được tạo theo thuật toán** – Cơ chế tạo mê cung có tính xác định, đảm bảo thế giới nhất quán giữa các thiết bị.
- **Nhiều tầng khác nhau** – Mỗi tầng có môi trường, bố cục và tiến trình riêng.
- **Tường Noclip** – Khám phá những bức tường không ổn định, cho phép di chuyển đến các tầng khác.
- **Nhiều người chơi (Multiplayer)** – Kết nối với máy chủ Multiplayer thông qua địa chỉ IP và cổng kết nối.
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
| Nút RUN | Chuyển đổi giữa đi bộ và chạy |
| Chính giữa phía trên | Tạm dừng / Cài đặt |
| Góc trên bên phải | Bật / tắt bản đồ thu nhỏ |
| Nút Back | Tạm dừng / Điều hướng menu |

### Cài đặt

- **Field of View (FOV):** Có thể điều chỉnh từ 30° đến 110°.
- **Fog:** Có thể tùy chỉnh cường độ sương mù.
- **Difficulty:** Bao gồm Easy, Normal, Hard và Nightmare.

## Multiplayer

Backrooms hỗ trợ kết nối với máy chủ Multiplayer.

1. Mở menu Multiplayer.
2. Nhập địa chỉ IP của máy chủ.
3. Có thể chỉ định cổng kết nối bằng định dạng `host:port`.
4. Nhấn Join để tham gia.

Nếu không chỉ định cổng, trò chơi sẽ sử dụng cổng mặc định.

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
| Nền tảng | Android |
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
│   └── src/
│       └── main/
│           ├── java/
│           │   └── ...
│           └── ...
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

## Giấy phép

`GPL-3.0-only` kể từ v1.0.28