# Backrooms

**Backrooms** là một trò chơi khám phá góc nhìn thứ nhất mang bầu không khí kinh dị, lấy cảm hứng từ vũ trụ Backrooms.

Khám phá những không gian vô tận mang cảm giác siêu thực, tìm đường qua các môi trường được tạo theo thuật toán, phát hiện những bức tường không ổn định, sống sót trước các thực thể nguy hiểm và tìm lối thoát qua các tầng khác nhau.

## Tính năng

- **Khám phá góc nhìn thứ nhất** – Khám phá những môi trường đậm chất không gian siêu thực với trải nghiệm nhập vai góc nhìn thứ nhất.
- **Môi trường được tạo theo thuật toán** – Mỗi ván chơi mới tạo ra một thế giới hoàn toàn ngẫu nhiên từ một seed riêng. Seed được lưu cùng bản lưu; trong multiplayer, máy chủ gửi seed của mình cho mọi người chơi nên ai cũng ở trong cùng một thế giới. Bản lưu và máy chủ phiên bản cũ vẫn mở đúng thế giới ban đầu.
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

## Bộ kết xuất

Khung hình mặc định ở 1080p (1920×1080). Có thể đổi trong Options: 270p, 360p, 540p, 720p hoặc 1080p (luôn là 16:9). Trên Android ở mục GRAPHICS → FRAME SIZE, trên desktop ở Options → FRAME SIZE. Độ phân giải thấp nhanh hơn vì chi phí tăng theo số pixel, và màn hình tự phóng to khung hình. Cách khung hình được vẽ phụ thuộc vào bộ kết xuất.

**Android** (Options → GRAPHICS → RENDERER, mặc định là Canvas):

- **Canvas**: CPU vẽ toàn bộ khung hình rồi Android hiển thị. Hoạt động trên mọi máy.
- **OpenGL ES** (cần ES 3.0, `gpu/GlesPresenter.kt`) và **Vulkan** (`app/src/main/cpp`, nạp `libvulkan` lúc chạy nên `minSdk` vẫn là 21): GPU tự vẽ hình 3D (sàn, trần, tường, sprite, hiệu ứng, minimap) từ dữ liệu cảnh bằng một shader dùng chung cho cả hai, `app/src/main/assets/shaders/scene.glsl`. CPU chỉ còn chiếu tia theo từng cột, tính đèn nhấp nháy và danh sách sprite (`Renderer.buildScene`), tốn chưa tới 1 ms thay vì hàng chục ms ở 1080p, và chi phí này không tăng theo độ phân giải.
- **Adreno / Freedreno / Turnip**: Trên GPU Qualcomm Adreno, Vulkan thường mượt hơn OpenGL ES. Driver hệ thống Qualcomm tốt; driver mã nguồn mở **Turnip** (phần Vulkan của Mesa Freedreno) thường hỗ trợ nhiều extension hơn và đôi khi nhanh hơn trên Adreno 6xx/7xx. Có thể dùng thư viện AdrenoTools (hoặc tương tự) để nạp driver Turnip tùy chỉnh cho app mà không cần root. Tên renderer hiển thị trong Options sẽ chứa "Adreno", "Freedreno" hoặc "Mesa" tùy driver.
- **FPS Limit & VSync** (Options → GRAPHICS): FPS Limit 30 / 60 / 90 / 120 / 144 / 240 / Ultimate (không giới hạn). VSync ON dùng FIFO (khóa theo màn hình); OFF dùng MAILBOX rồi IMMEDIATE khi driver hỗ trợ. Đổi VSync sẽ tạo lại presenter. FPS Limit áp dụng bằng sleep trên vòng lặp game, hoạt động cùng VSync.
- **Quality** Low / Medium / High (kiểu Sodium): Low = ít bước DDA/sprite hơn, tắt grain/glitch/gợn nước; Medium cân bằng; High đủ fidelity. GLES đọc `scene.glsl` lúc chạy; sau khi sửa GLSL chạy `app/src/main/cpp/shaders/compile_shaders.sh` để cập nhật SPIR-V Vulkan.

Bốn level thế giới mở (City, Island, Graveyard và Hortus Aeturnus) có mặt đất và tòa nhà được sinh bằng mã riêng, chưa chuyển sang shader, nên ở đó CPU vẫn vẽ khung hình rồi đưa lên GPU để phóng to.

Nếu backend được chọn không khởi động được, trò chơi tự quay về Canvas và Options hiển thị lý do.

**Desktop** (Linux + Mesa): `java -jar backrooms.jar --zink` chạy pipeline OpenGL của Java2D trên Zink (OpenGL chạy trên Vulkan). Nếu GPU của bạn chỉ có Vulkan CPU như lavapipe, đặt thêm `LIBGL_ALWAYS_SOFTWARE=1`.

**Kiểm thử trên máy tính**: `tools/vk-host-test/run.sh [rộng cao]` chạy cùng mã C++ với bản Android trên X11, rồi đọc lại cửa sổ để kiểm tra màu, chiều ảnh, đổi kích thước và tạo lại bề mặt. `tools/scene-test` so sánh bộ kết xuất GPU với bản CPU: `Cases.kt` dựng 29 tình huống (mọi level mê cung, các loại quái, vật phẩm, hiệu ứng, minimap), `SceneDump.kt` xuất khung CPU cùng dữ liệu cảnh, rồi `run.sh` (Vulkan) và `run-gles.sh` (OpenGL ES) vẽ lại bằng shader và so theo khối 16×16, vì nhiễu hạt cùng cường độ nhưng khác dãy số ngẫu nhiên. Cần `libvulkan-dev`, `libx11-dev` và một driver Vulkan (`mesa-vulkan-drivers`). `tools/hortus-test/HortusTest.kt` kiểm tra cơ chế của Level 414 (cổng và dây leo, ước muốn rời đi, đường ra, Ivlania, lưu game). Sau khi sửa shader trong `app/src/main/cpp/shaders`, chạy `compile_shaders.sh` (cần `glslang-tools`) để cập nhật `vk_shaders.h`.
## Links & Social 
 
- Discord: https://discord.gg/DfpyAs9TaZ

## Giấy phép, danh đề và ghi công

### Giấy phép

Mã nguồn của **Backrooms** được phát hành theo giấy phép **GNU General Public License v3.0 (GPL-3.0-only)**.

Các nội dung bên thứ ba được sử dụng trong dự án, bao gồm cấp độ (Level), hình ảnh, âm thanh, tài liệu và các tài nguyên khác, có thể được áp dụng giấy phép riêng. Những nội dung này không mặc nhiên thuộc giấy phép của mã nguồn.

Đối với các cấp độ được lấy từ Backrooms Wikidot, giấy phép và điều kiện sử dụng được áp dụng theo giấy phép của từng nội dung gốc. Các nội dung thuộc **Creative Commons Attribution-ShareAlike 3.0 (CC BY-SA 3.0)** sẽ được ghi nhận và phân phối theo đúng các điều kiện của giấy phép đó.

### Danh đề và ghi công

Backrooms xin ghi nhận công sức sáng tạo của cộng đồng Backrooms, đặc biệt là những tác giả đã đóng góp các cấp độ, ý tưởng, hình ảnh và nội dung được sử dụng làm nguồn cảm hứng hoặc đưa vào trò chơi.

- **Backrooms Wikidot:** Nguồn tham khảo cho các cấp độ và nội dung thuộc vũ trụ Backrooms.
- **Các tác giả gốc:** Được ghi nhận theo tên tác giả và liên kết đến tác phẩm gốc khi có sử dụng nội dung của họ.
- **Các cộng tác viên:** Ghi nhận những cá nhân đã đóng góp mã nguồn, sửa lỗi, cải thiện hiệu năng và phát triển trò chơi.
- **Cộng đồng Backrooms:** Cảm ơn cộng đồng đã xây dựng, mở rộng và duy trì thế giới Backrooms.

Danh sách chi tiết các nội dung bên thứ ba, tác giả, nguồn gốc và giấy phép tương ứng sẽ được ghi nhận trong tài liệu `THIRD_PARTY_LICENSES.md` (nếu có).

### Tuyên bố miễn trừ

**Backrooms** là dự án do cộng đồng phát triển, lấy cảm hứng từ vũ trụ Backrooms. Dự án không tuyên bố sở hữu các tác phẩm, ý tưởng hoặc tài sản trí tuệ thuộc về những tác giả và tổ chức khác.

Mọi quyền đối với nội dung của bên thứ ba vẫn thuộc về chủ sở hữu tương ứng.