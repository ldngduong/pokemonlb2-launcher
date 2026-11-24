# Pokémon Legend Black 2 - Launcher

Bộ khởi chạy chính thức (Official Launcher) cho dự án Pokémon Legend Black 2 trên Windows và Android.

## Tính năng (Windows Launcher)
- **Tự động tải & cập nhật ROM:** Tự động kết nối máy chủ CDN GitHub Releases để kiểm tra phiên bản mới và tải bản cập nhật ROM trực tiếp.
- **Bảo toàn Save 100%:** Tự động sao lưu tiến trình save (`.sav`) vào thư mục `backups/`, tự động di chuyển và đồng bộ save sang file ROM mới mỗi khi nâng cấp.
- **Tích hợp giả lập melonDS:** Thiết lập sẵn cấu hình tối ưu để chơi mượt mà với 1 click.
- **Giao diện hiện đại:** Canvas render tối ưu không giật lag, tự động phát hiện trạng thái ROM & Save.

## Cấu trúc thư mục khi giải nén
```text
PokemonLegendBlack2-Windows/
├── Pokemon Legend Black 2.exe   # File chạy chính
├── emulator/                    # Giả lập melonDS tối ưu
├── assets/                      # Hình ảnh & giao diện
├── rom/                         # Nơi chứa file ROM (.nds) và Save (.sav)
├── backups/                     # Nơi tự động lưu trữ các bản backup save
└── version.json                 # Thông tin phiên bản hiện tại
```

## Build từ mã nguồn (Windows)
Yêu cầu Python 3.10+ và các thư viện trong `requirements.txt`:
```bash
pip install pillow pyinstaller
pyinstaller "Pokemon Legend Black 2.spec" --noconfirm
```
File thực thi hoàn chỉnh sẽ được tạo trong thư mục `dist/`.
