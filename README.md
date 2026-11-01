# Quản lý nhà xe (Next.js + Spring Boot + MySQL)

## Yêu cầu
- JDK 17+, Maven 3.8+ (hoặc dùng IntelliJ / Eclipse / VS Code mở thư mục này)
- Node.js 20.9+ và npm (giao diện Next.js trong `web/`)
- Để dùng database MySQL: MySQL 8 đang chạy ở `localhost:3306`
- MySQL là database mặc định. Có thể chủ động chọn H2 local khi MySQL không khả dụng.
- IDE cần bật **Lombok** (IntelliJ: bật Enable annotation processing)

## Chạy
1. Để dùng MySQL, khởi động MySQL rồi chạy task **Run Parking Management Web App** trong VS Code. Task khởi động Spring API tại `http://localhost:8080`, Next.js tại `http://localhost:3000` và mở giao diện Next.js. Spring dùng database riêng `parking_management` theo cấu hình trong `application.properties`; MySQL tự tạo database mới nếu tài khoản có quyền, sau đó Hibernate cập nhật schema với `DB_DDL_AUTO=update` (mặc định). Database cũ `quanly_nhaxe` không bị sửa.
2. Có thể chạy thủ công trong hai terminal:
   - Windows: `.\mvnw.cmd spring-boot:run`
   - macOS/Linux: `./mvnw spring-boot:run`
   - Terminal thứ hai: `cd web` rồi chạy `npm run dev`. Truy cập giao diện tại `http://localhost:3000`; Next.js mặc định proxy API tới Spring ở `http://localhost:8080`.
3. Nếu MySQL cần thông tin khác, cấu hình biến môi trường trước khi chạy (không ghi mật khẩu vào source):
   - PowerShell: `$env:DB_URL="jdbc:mysql://localhost:3306/parking_management?useSSL=false&serverTimezone=Asia/Ho_Chi_Minh"; $env:DB_USERNAME="root"; $env:DB_PASSWORD="<mat-khau>"`
   - Có thể đặt thêm `DB_DDL_AUTO=validate` để yêu cầu schema có sẵn và tránh Hibernate tự sửa cấu trúc database.
4. Nếu không có MySQL, trong VS Code chọn **Run Parking Management Web App (H2)**. Spring API vẫn chạy tại `http://localhost:8080`, Next.js tại `http://localhost:3000`; H2 lưu bền vững trong `data/quanly_nhaxe`, tách biệt với MySQL và không tự đồng bộ giữa hai database. Để quay lại MySQL, chọn **Run Parking Management Web App**.
5. Mở `http://localhost:3000` nếu trình duyệt chưa tự mở. Task H2 không cần MySQL; task MySQL yêu cầu MySQL đang chạy và sẽ báo lỗi nếu không kết nối được.
   - Quản trị: `admin` / `admin123`
   - Nhân viên: `staff` / `staff123`
   - Khách hàng: chọn **Đăng ký đặt chỗ** tại trang đăng nhập, sau đó đăng nhập để xem chỗ trống và đặt vị trí theo giờ.

## Đặt chỗ trực tuyến
Khách hàng đăng ký tài khoản riêng, chọn loại xe, thời gian nhận/trả và vị trí còn trống. Đơn được lưu vào bảng SQL `parking_reservation` ngay khi tạo, gồm mã đặt chỗ, khách hàng, biển số, vị trí, thời gian, giá và trạng thái; thông tin được giữ lại khi khởi động lại ứng dụng. Giá theo giờ được quản trị viên cấu hình tại **Bảng giá**; thời lượng lẻ được làm tròn lên giờ tiếp theo. Đơn đặt giữ chỗ ở trạng thái chờ trong lúc khách chuyển khoản QR. Nhân viên/quản trị viên đối chiếu giao dịch ngân hàng tại **Đặt chỗ online** rồi xác nhận thủ công; hệ thống chưa tự kết nối ngân hàng. Khi được xác nhận, thông báo được lưu theo tài khoản khách trong SQL và đẩy tức thời tới trang khách đang mở qua SSE; trang khách đồng thời kiểm tra định kỳ để đồng bộ khi kết nối trực tiếp bị gián đoạn, và tự tạo thông báo còn thiếu cho các đơn đã xác nhận trước đó. Khách có thể hủy đơn khi chưa thanh toán và trước giờ nhận xe. Khi xe đến, nhân viên check-in theo biển số/vị trí đã xác nhận.

Trang chủ `/` giới thiệu dịch vụ, quy trình đặt chỗ và bảng giá hiện tại. Dashboard khách hàng là trang này tại `/dashboard`; sau đăng nhập, khách hàng được chuyển đến `/dashboard`, và truy cập `/` cũng đưa khách về dashboard. Nút **Đặt chỗ** mở trang `/reservations`, nơi khách xem đơn, chọn xe, thời gian và vị trí trống. Giá theo giờ và tình trạng chỗ lấy từ database, không dùng giá/vị trí mẫu. Nhân viên và quản trị viên dùng khu vực vận hành tại `/admin`; các màn hình quản lý tài khoản, xe, vé tháng, doanh thu, bảng giá, đặt chỗ online và hỗ trợ đều nằm dưới `/admin`.

Trang `/contact` lưu mỗi yêu cầu vào bảng SQL `contact_inquiry` trước khi gửi email. Trạng thái gửi email (`SENT` hoặc `FAILED`) cũng được lưu; nếu SMTP chưa cấu hình hoặc gửi thất bại, lời nhắn vẫn còn trong database và giao diện báo rõ cho khách. Cấu hình SMTP bằng `MAIL_HOST`, `MAIL_PORT`, `MAIL_USERNAME`, `MAIL_PASSWORD`; có thể đặt `CONTACT_FROM` để dùng địa chỉ gửi riêng và `CONTACT_EMAIL` để đổi email nhận. Không lưu thông tin SMTP trong source code.

Khách hàng có thể mở nút **Chat hỗ trợ** ở góc màn hình để nhắn trực tiếp với quản trị viên; mỗi tin nhắn được lưu thành một dòng trong bảng SQL `support_chat_message`, không lưu chỉ ở trình duyệt. Mặc định ứng dụng dùng MySQL `parking_management`; Hibernate tự tạo/cập nhật bảng khi `DB_DDL_AUTO=update` (mặc định). Tin nhắn mới đồng thời cập nhật tức thời qua SSE khi hai bên đang trực tuyến. Quản trị viên trả lời tại **Hộp thư hỗ trợ**. Nếu quản trị viên ngoại tuyến, tin nhắn vẫn được lưu và hiện trong hộp thư khi quản trị viên quay lại. H2 là lựa chọn tách biệt để phát triển/test và không tự đồng bộ với MySQL.

Báo cáo doanh thu tính tiền vé lượt khi xe check-out, vé tháng theo ngày đăng ký, và tiền đặt chỗ online tại thời điểm nhân viên xác nhận thanh toán. Đơn đặt chỗ còn chờ hoặc đã hủy không được tính; báo cáo ngày, biểu đồ, cơ cấu nguồn, doanh thu theo loại xe và CSV cùng bao gồm khoản đặt chỗ đã thanh toán.

Đăng nhập được xác thực từ bảng `users` bằng mật khẩu BCrypt. Các trang tổng quan, bãi xe, vé tháng, lịch sử và báo cáo đọc/ghi vào database đang được chọn. Database MySQL `parking_management` và schema được tự tạo/cập nhật theo cấu hình mặc định; tài khoản mẫu và bảng giá chỉ được thêm nếu các bảng tương ứng đang trống. Database MySQL cũ `quanly_nhaxe` được giữ nguyên và không tự động chuyển dữ liệu sang database mới. Đổi `DB_DDL_AUTO=validate` khi kết nối database đã được quản lý sẵn.

## Chức năng
| Chức năng | ADMIN | STAFF |
|---|:-:|:-:|
| Đăng nhập / đăng xuất, mã hóa mật khẩu BCrypt | ✔ | ✔ |
| Sơ đồ bãi xe: sức chứa từng loại xe, lượt xe & doanh thu hôm nay | ✔ | ✔ |
| Xe vào / ra, tự tính tiền, chặn xe trùng, chặn khi bãi đầy | ✔ | ✔ |
| Lịch sử xe vào / ra + tìm kiếm và phân trang; ADMIN lọc theo nhân viên và xem người thực hiện từng chiều | ✔ | ✔ |
| Đăng ký vé tháng (xe có vé còn hạn vào/ra miễn phí) | ✔ | ✔ |
| Đổi tên chủ xe/biển số vé tháng | ✔ | ✘ |
| Ngừng gia hạn hoặc hủy vé tháng và tính khoản hoàn theo ngày còn lại | ✔ | ✘ |
| Thanh toán QR, tiền mặt hoặc thẻ tín dụng qua POS; nhân viên xác minh thủ công trước khi check-out | ✔ | ✔ |
| Xóa vé tháng | ✔ | ✘ |
| Báo cáo doanh thu theo ngày / loại xe + xuất CSV | ✔ | ✘ |
| Chỉnh bảng giá, sức chứa | ✔ | ✘ |
| Hộp thư hỗ trợ khách hàng trực tuyến | ✔ | ✘ |
| Quản lý tài khoản (tạo, sửa tên đăng nhập/họ tên, đổi mật khẩu, khóa/mở khóa, xóa; chỉ ADMIN) | ✔ | ✘ |

Cách tính phí: vé lượt tính theo giá ngày và số ngày lịch (gửi qua đêm tính thêm 1 ngày). Đơn đặt chỗ online đã thanh toán được miễn phí bổ sung nếu xe ra trước hoặc đúng giờ kết thúc đã đặt; nếu trả trễ, tính phụ thu theo giá đặt chỗ mỗi giờ, làm tròn lên mỗi giờ bắt đầu. Nhân viên chỉ xác nhận cho xe ra sau khi thu đủ phần phụ thu này.

Khách hàng có dashboard cá nhân tại `/dashboard` để xem đơn sắp tới, số đơn chờ thanh toán, đơn đã xác nhận và lịch sử gần đây. Nhân viên và ADMIN dùng dashboard vận hành tại `/admin`; sau đăng nhập, tài khoản quản trị/nhân viên được chuyển thẳng đến địa chỉ này.

Thanh toán khi trả xe hỗ trợ QR MB Bank, tiền mặt và thẻ tín dụng qua máy POS riêng. QR sử dụng ảnh tĩnh do chủ hệ thống cung cấp (`src/main/resources/payment/mb-bank-qr.jpg`), được phục vụ qua endpoint cần đăng nhập; QR không tự điền số tiền và hệ thống không tự xác minh giao dịch. Với tiền mặt, nhân viên xác nhận đã nhận đủ tiền; với QR/thẻ tín dụng, nhân viên xác nhận sau khi kiểm tra giao dịch thành công trong ứng dụng ngân hàng hoặc máy POS. Hệ thống lưu phương thức thanh toán trên lịch sử, nhưng chưa kết nối trực tiếp với ngân hàng hoặc POS. Xe có vé tháng hợp lệ không cần thanh toán và được ghi nhận là vé tháng.

Quản lý vé tháng cho phép ADMIN đổi tên chủ xe/biển số, ngừng gia hạn (vé vẫn dùng đến hết hạn), hoặc hủy ngay. Hủy ngay tính khoản hoàn theo tỷ lệ số ngày chưa sử dụng trên tổng số ngày vé và lưu số tiền cần hoàn để nhân viên hoàn thủ công; ứng dụng không tự chuyển tiền. Danh sách chỉ gộp hiển thị các vé cùng biển số, cùng loại xe và có thời hạn chồng lấn; dữ liệu gốc vẫn được giữ. Dòng gộp hiển thị ngày sử dụng duy nhất (đã dùng/còn lại), tổng giá và khoản hoàn dự kiến nếu hủy hôm nay; vé đã hủy hiển thị khoản hoàn đã tính. Đăng ký mới bị chặn khi cùng biển số và loại xe đã có vé chưa hủy giao thời hạn. Vé chỉ miễn phí khi biển số, loại xe và thời hạn của vé đều trùng với lượt check-in. Hệ thống hiện không tự động thu phí hoặc gia hạn vé.

Sơ đồ dashboard vận hành dùng sức chứa trong `pricing` và các xe đang có trong `parking_record`. Mã loại xe máy điện được chuẩn hóa thành `MOTORELECTRIC` trong `pricing`, `parking_record` và `monthly_ticket`; mã cũ `BICYCLE` được tự chuyển khi ứng dụng khởi động. Mỗi lượt vào bãi lưu mã vị trí (`CAR-001`, `MOTORBIKE-001`, `MOTORELECTRIC-001`) trong cột `slot_code`; mã vị trí cũ `BICYCLE-###` và `ELECTRICMOTORCYCLE-###` cũng được chuyển sang tiền tố `MOTORELECTRIC-###` mà giữ nguyên số vị trí. Các lượt đang ở bãi chưa có mã vị trí sẽ được gán vị trí trống khi nhân viên mở dashboard vận hành lần đầu sau khi cập nhật. Lịch sử lưu tài khoản nhân viên check-in (`created_by`) và check-out (`checked_out_by`); Hibernate tự thêm cột `checked_out_by` khi ứng dụng khởi động. Các lượt đã ra bãi trước thay đổi này sẽ không có thông tin nhân viên check-out cũ.

Next.js là giao diện chính. Các template Spring/Thymeleaf hiện được giữ làm đường tương thích trong thời gian kiểm tra parity; không cần mở cổng 8080 trong trình duyệt để sử dụng giao diện Next.js.

## Cấu trúc
```
entity/      AppUser, Pricing, MonthlyTicket, ParkingRecord, VehicleType
repository/  Spring Data JPA
service/     ParkingService, ReportService, SupportChatService, CustomUserDetailsService
controller/  Auth, Dashboard, Parking, Ticket, Report, Pricing, User, SupportChat và API
web/         Giao diện Next.js App Router
templates/   Giao diện Thymeleaf tương thích trong giai đoạn chuyển đổi
database/    schema.sql (tham khảo / vẽ ERD)
```
