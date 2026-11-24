import os, sys, json, time, shutil, subprocess, hashlib, threading, ctypes, re, webbrowser
import urllib.request, urllib.error, urllib.parse
import tkinter as tk
from tkinter import messagebox
from PIL import Image, ImageDraw, ImageFont, ImageTk

APP_TITLE = "Pokémon Legend Black 2"

# Safe directory resolution for both script and PyInstaller frozen executable
if getattr(sys, 'frozen', False):
    APP_DIR = os.path.dirname(os.path.abspath(sys.executable))
else:
    script_dir = os.path.dirname(os.path.abspath(__file__))
    win_pkg_dir = os.path.join(script_dir, "PokemonLegendBlack2-Windows")
    if os.path.exists(win_pkg_dir) and os.path.isdir(win_pkg_dir):
        APP_DIR = win_pkg_dir
    else:
        APP_DIR = script_dir

# Unified folder architecture: ROM and Save live together in ROM_DIR
ROM_DIR = os.path.join(APP_DIR, "rom")
BACKUP_DIR = os.path.join(APP_DIR, "backups")
EMU_DIR = os.path.join(APP_DIR, "emulator")
OLD_SAVE_DIR = os.path.join(APP_DIR, "saves")
VERSION_FILE = os.path.join(APP_DIR, "version.json")

GITHUB_REPO = "ldngduong/pokemon-legend-black2"
GITHUB_API_LATEST = f"https://api.github.com/repos/{GITHUB_REPO}/releases/latest"
GITHUB_RELEASES_PAGE = f"https://github.com/{GITHUB_REPO}/releases"

DEFAULT_SERVER_URL = "https://ota.duongle.dev"
APP_USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36 LegendLauncher/1.0"

WIN_W = 960
WIN_H = 540

def get_asset_path(rel_path):
    for base in [APP_DIR, getattr(sys, "_MEIPASS", ""), r"C:\Work\bw2"]:
        if base:
            full = os.path.join(base, rel_path)
            if os.path.exists(full):
                return full
    return os.path.join(APP_DIR, rel_path)

def get_custom_font(size, bold=True):
    name = "FzUniversBold.ttf" if bold else "FzUnivers.ttf"
    candidates = [
        get_asset_path(os.path.join("assets", "fonts", name)),
        os.path.join(r"C:\Users\letun\AppData\Local\Microsoft\Windows\Fonts", name),
        get_asset_path(os.path.join("assets", "fonts", "FzUniversBold.ttf")),
        get_asset_path(os.path.join("assets", "fonts", "FzUnivers.ttf")),
    ]
    for c in candidates:
        if os.path.exists(c):
            try:
                return ImageFont.truetype(c, size)
            except Exception:
                pass
    return ImageFont.load_default()

# Fast BPS engine
class BPS:
    @staticmethod
    def decode_number(data, offset):
        val = 0
        shift = 1
        while True:
            b = data[offset]
            offset += 1
            val += (b & 0x7F) * shift
            if b & 0x80:
                break
            shift <<= 7
            val += shift
        return val, offset

    @classmethod
    def apply_patch(cls, src, patch):
        import zlib, struct
        assert patch[:4] == b"BPS1", "Invalid BPS header"
        offset = 4
        s_len, offset = cls.decode_number(patch, offset)
        t_len, offset = cls.decode_number(patch, offset)
        m_len, offset = cls.decode_number(patch, offset)
        offset += m_len

        target = bytearray(t_len)
        out_pos = 0
        src_pos = 0
        patch_end = len(patch) - 12

        while offset < patch_end:
            data, offset = cls.decode_number(patch, offset)
            action = data & 3
            length = (data >> 2) + 1

            if action == 0:
                target[out_pos:out_pos+length] = src[src_pos:src_pos+length]
                out_pos += length
                src_pos += length
            elif action == 1:
                target[out_pos:out_pos+length] = patch[offset:offset+length]
                out_pos += length
                offset += length
            elif action == 2:
                offset_data, offset = cls.decode_number(patch, offset)
                src_pos += (offset_data >> 1) if (offset_data & 1) == 0 else -(offset_data >> 1)
                target[out_pos:out_pos+length] = src[src_pos:src_pos+length]
                out_pos += length
                src_pos += length
            elif action == 3:
                offset_data, offset = cls.decode_number(patch, offset)
                t_copy = out_pos + ((offset_data >> 1) if (offset_data & 1) == 0 else -(offset_data >> 1))
                for _ in range(length):
                    target[out_pos] = target[t_copy]
                    out_pos += 1
                    t_copy += 1

        s_crc, t_crc, p_crc = struct.unpack('<III', patch[-12:])
        assert (zlib.crc32(src) & 0xFFFFFFFF) == s_crc, "Source CRC mismatch"
        assert (zlib.crc32(target) & 0xFFFFFFFF) == t_crc, "Target CRC mismatch"
        return bytes(target)

def extract_version_from_rom_dir():
    if not os.path.exists(ROM_DIR):
        return None, None
    nds_files = [f for f in os.listdir(ROM_DIR) if f.lower().endswith(".nds") and not f.endswith(".part")]
    if not nds_files:
        return None, None
    nds_files.sort(reverse=True)
    picked = nds_files[0]
    m = re.search(r"Beta\s*([0-9.]+)", picked, re.I)
    v = f"Beta {m.group(1)}" if m else ""
    return picked, v

def init_environment():
    for d in [ROM_DIR, BACKUP_DIR, EMU_DIR]:
        os.makedirs(d, exist_ok=True)
    if not os.path.exists(VERSION_FILE):
        rom_file, detected_v = extract_version_from_rom_dir()
        default_v = {
            "version": detected_v if detected_v else "",
            "rom_file": rom_file if rom_file else "",
            "last_updated": time.strftime("%Y-%m-%d %H:%M:%S")
        }
        with open(VERSION_FILE, "w", encoding="utf-8") as f:
            json.dump(default_v, f, indent=2, ensure_ascii=False)

def get_current_version():
    data = {}
    if os.path.exists(VERSION_FILE):
        try:
            with open(VERSION_FILE, "r", encoding="utf-8") as f:
                data = json.load(f)
        except Exception:
            pass

    # Dynamic check: if ROM does not exist in rom/, version is empty
    rom_file, detected_v = extract_version_from_rom_dir()
    if rom_file and detected_v:
        if not data.get("version"):
            data["version"] = detected_v
        if not data.get("rom_file"):
            data["rom_file"] = rom_file
    elif not rom_file:
        data["version"] = ""
        data["rom_file"] = ""

    return data

def set_current_version(v_data):
    try:
        with open(VERSION_FILE, "w", encoding="utf-8") as f:
            json.dump(v_data, f, indent=2, ensure_ascii=False)
    except Exception as e:
        print("Save version error:", e)

class LauncherGUI:
    def __init__(self, root):
        self.root = root
        self.root.title(APP_TITLE)
        self.root.geometry(f"{WIN_W}x{WIN_H}")
        self.root.resizable(False, False)
        self.root.configure(bg="#000000")

        # Center window on screen
        sw = self.root.winfo_screenwidth()
        sh = self.root.winfo_screenheight()
        wx = (sw - WIN_W) // 2
        wy = (sh - WIN_H) // 2
        self.root.geometry(f"{WIN_W}x{WIN_H}+{wx}+{wy}")

        # Borderless window with taskbar icon presence
        self.root.overrideredirect(True)
        self.setup_taskbar_presence()

        init_environment()
        self.ver_data = get_current_version()
        self.current_save_info = None
        self.is_overlay_open = False
        self.dl_cancelled = False

        self.setup_canvas()
        self.refresh_all_status()

        # Window focus listener for auto-refreshing save/rom status
        self.root.bind("<FocusIn>", lambda e: self.on_window_focus())

        # Check for updates on startup if ROM is present
        self.root.after(600, self.trigger_startup_update_check)

    def setup_taskbar_presence(self):
        try:
            self.root.update_idletasks()
            hwnd = ctypes.windll.user32.GetParent(self.root.winfo_id())
            if not hwnd:
                hwnd = self.root.winfo_id()
            GWL_EXSTYLE = -20
            WS_EX_APPWINDOW = 0x00040000
            WS_EX_TOOLWINDOW = 0x00000080
            style = ctypes.windll.user32.GetWindowLongW(hwnd, GWL_EXSTYLE)
            style = (style & ~WS_EX_TOOLWINDOW) | WS_EX_APPWINDOW
            ctypes.windll.user32.SetWindowLongW(hwnd, GWL_EXSTYLE, style)
            self.root.withdraw()
            self.root.after(10, self.root.deiconify)
        except Exception as e:
            print("Taskbar style warning:", e)

    def setup_canvas(self):
        self.canvas = tk.Canvas(self.root, width=WIN_W, height=WIN_H, highlightthickness=0, bg="#000000")
        self.canvas.pack(fill="both", expand=True)

        # 1. Render background with gradient
        self.render_background()

        # 2. Render pill buttons
        self.render_buttons()

        # 3. Setup window drag on top bar
        self.canvas.bind("<ButtonPress-1>", self.on_start_drag)
        self.canvas.bind("<B1-Motion>", self.on_drag_motion)

    def render_background(self):
        wall_file = get_asset_path(os.path.join("assets", "wallpaper.jpg"))
        if os.path.exists(wall_file):
            wall = Image.open(wall_file).convert("RGBA")
            # Auto-crop any black letterbox bars if present in source (threshold 20)
            gray = wall.convert("L")
            bw = gray.point(lambda p: 255 if p > 20 else 0)
            bbox = bw.getbbox()
            if bbox:
                wall = wall.crop(bbox)
        else:
            wall = Image.new("RGBA", (WIN_W, WIN_H), (15, 23, 42, 255))

        w_orig, h_orig = wall.size
        scale = max(WIN_W / w_orig, WIN_H / h_orig)
        new_w = int(w_orig * scale)
        new_h = int(h_orig * scale)
        wall_scaled = wall.resize((new_w, new_h), Image.Resampling.LANCZOS)
        x_crop = (new_w - WIN_W) // 2
        y_crop = (new_h - WIN_H) // 2
        bg = wall_scaled.crop((x_crop, y_crop, x_crop + WIN_W, y_crop + WIN_H))

        # Bottom dark gradient overlay for text and button contrast
        gradient = Image.new("RGBA", (WIN_W, WIN_H), (0, 0, 0, 0))
        g_draw = ImageDraw.Draw(gradient)
        for y in range(WIN_H):
            if y > 260:
                alpha = int(((y - 260) / (WIN_H - 260)) ** 1.35 * 230)
                g_draw.line([(0, y), (WIN_W, y)], fill=(5, 8, 15, alpha))

        # Top Header Bar: Dark Glass with crisp divider and soft drop shadow
        # 1. Dark glass bar across y=0 to 42
        for y in range(42):
            alpha = int(170 + (y / 42.0) * 45)
            g_draw.line([(0, y), (WIN_W, y)], fill=(7, 10, 18, alpha))

        # 2. Subtle separator line at y=42
        g_draw.line([(0, 42), (WIN_W, 42)], fill=(255, 255, 255, 30))

        # 3. Soft drop shadow beneath header from y=43 to 58 (15px smooth quadratic falloff)
        for y in range(43, 58):
            factor = 1.0 - ((y - 43) / 15.0)
            alpha = int(factor * factor * 115)
            g_draw.line([(0, y), (WIN_W, y)], fill=(0, 0, 0, alpha))

        self.bg_img = Image.alpha_composite(bg, gradient)
        draw = ImageDraw.Draw(self.bg_img)

        # Header Title: Small elegant tracking on top left of draggable bar
        mini_font = get_custom_font(10, bold=True)
        draw.text((16, 14), "POKÉMON LEGEND BLACK 2", font=mini_font, fill=(148, 163, 184, 180))

        # Title: POKEMON LEGEND BLACK 2
        title_font = get_custom_font(34, bold=True)
        draw.text((45, 385), "POKEMON LEGEND BLACK 2", font=title_font, fill=(255, 255, 255, 255))

        self.bg_photo = ImageTk.PhotoImage(self.bg_img)
        self.canvas.create_image(0, 0, anchor="nw", image=self.bg_photo, tags="bg")

        # Status Line BELOW 3 Buttons (Neutral silver/gray #94A3B8, NOT GREEN)
        self.status_item = self.canvas.create_text(
            47, 494, anchor="w",
            text="Đang kiểm tra trạng thái...",
            font=("Segoe UI", 9), fill="#94A3B8", tags="status_badge"
        )

        # Pre-render dim backdrop for in-canvas modals
        dim_sheet = Image.new("RGBA", (WIN_W, WIN_H), (5, 8, 15, 205))
        self.dim_bg_img = Image.alpha_composite(self.bg_img, dim_sheet)
        self.dim_photo = ImageTk.PhotoImage(self.dim_bg_img)

        # Header Window Controls (centered in 42px bar, Y=21)
        # Minimize Button (Vector Line)
        self.min_line = self.canvas.create_line(WIN_W - 55, 21, WIN_W - 41, 21, fill="#CBD5E1", width=2, tags="win_min")
        self.min_hit = self.canvas.create_rectangle(WIN_W - 60, 6, WIN_W - 36, 36, fill="", outline="", tags="win_min")
        self.canvas.tag_bind("win_min", "<Button-1>", lambda e: self.minimize_window())
        self.canvas.tag_bind("win_min", "<Enter>", lambda e: (self.canvas.itemconfig(self.min_line, fill="#38BDF8"), self.canvas.config(cursor="hand2")))
        self.canvas.tag_bind("win_min", "<Leave>", lambda e: (self.canvas.itemconfig(self.min_line, fill="#CBD5E1"), self.canvas.config(cursor="")))

        # Close Button (Two Vector Crossed Lines)
        self.close_l1 = self.canvas.create_line(WIN_W - 27, 15, WIN_W - 15, 27, fill="#CBD5E1", width=2, tags="win_close")
        self.close_l2 = self.canvas.create_line(WIN_W - 15, 15, WIN_W - 27, 27, fill="#CBD5E1", width=2, tags="win_close")
        self.close_hit = self.canvas.create_rectangle(WIN_W - 32, 6, WIN_W - 10, 36, fill="", outline="", tags="win_close")
        self.canvas.tag_bind("win_close", "<Button-1>", lambda e: self.root.destroy())
        self.canvas.tag_bind("win_close", "<Enter>", lambda e: (self.canvas.itemconfig(self.close_l1, fill="#EF4444"), self.canvas.itemconfig(self.close_l2, fill="#EF4444"), self.canvas.config(cursor="hand2")))
        self.canvas.tag_bind("win_close", "<Leave>", lambda e: (self.canvas.itemconfig(self.close_l1, fill="#CBD5E1"), self.canvas.itemconfig(self.close_l2, fill="#CBD5E1"), self.canvas.config(cursor="")))

    def minimize_window(self):
        try:
            hwnd = ctypes.windll.user32.GetParent(self.root.winfo_id())
            if not hwnd: hwnd = self.root.winfo_id()
            ctypes.windll.user32.ShowWindow(hwnd, 6) # SW_MINIMIZE
        except Exception:
            self.root.iconify()

    def make_pill_texture(self, w, h, bg_color, border_color, border_w, text, text_color, bold=True, font_size=13):
        scale = 4
        img = Image.new("RGBA", (w * scale, h * scale), (0, 0, 0, 0))
        d = ImageDraw.Draw(img)
        radius = (h * scale) // 2
        d.rounded_rectangle(
            [(0, 0), (w * scale - 1, h * scale - 1)],
            radius=radius,
            fill=bg_color,
            outline=border_color,
            width=border_w * scale if border_color else 0
        )
        t_font = get_custom_font(font_size * scale, bold=bold)
        bbox = d.textbbox((0, 0), text, font=t_font)
        tw = bbox[2] - bbox[0]
        th = bbox[3] - bbox[1]
        tx = (w * scale - tw) // 2 - bbox[0]
        ty = (h * scale - th) // 2 - bbox[1]
        d.text((tx, ty), text, font=t_font, fill=text_color)
        resized = img.resize((w, h), Image.Resampling.LANCZOS)
        return ImageTk.PhotoImage(resized)

    def render_buttons(self):
        # Button 1: VÀO GAME
        w1, h1 = 145, 42
        self.img_play_normal = self.make_pill_texture(w1, h1, (255, 255, 255, 255), None, 0, "VÀO GAME", (0, 0, 0, 255))
        self.img_play_hover = self.make_pill_texture(w1, h1, (235, 240, 250, 255), (56, 189, 248, 255), 1, "VÀO GAME", (0, 0, 0, 255))
        self.btn_play_pos = (45, 436)
        self.item_play = self.canvas.create_image(self.btn_play_pos[0], self.btn_play_pos[1], anchor="nw", image=self.img_play_normal, tags="btn_play")
        self.canvas.tag_bind("btn_play", "<Enter>", lambda e: (self.canvas.config(cursor="hand2"), self.canvas.itemconfig(self.item_play, image=self.img_play_hover)))
        self.canvas.tag_bind("btn_play", "<Leave>", lambda e: (self.canvas.config(cursor=""), self.canvas.itemconfig(self.item_play, image=self.img_play_normal)))
        self.canvas.tag_bind("btn_play", "<Button-1>", lambda e: self.on_click_play())

        # Button 2: UPDATE
        w2, h2 = 125, 42
        self.img_update_normal = self.make_pill_texture(w2, h2, (10, 15, 25, 160), (255, 255, 255, 200), 1, "UPDATE", (255, 255, 255, 255))
        self.img_update_hover = self.make_pill_texture(w2, h2, (255, 255, 255, 40), (255, 255, 255, 255), 2, "UPDATE", (255, 255, 255, 255))
        self.btn_update_pos = (205, 436)
        self.item_update = self.canvas.create_image(self.btn_update_pos[0], self.btn_update_pos[1], anchor="nw", image=self.img_update_normal, tags="btn_update")
        self.canvas.tag_bind("btn_update", "<Enter>", lambda e: (self.canvas.config(cursor="hand2"), self.canvas.itemconfig(self.item_update, image=self.img_update_hover)))
        self.canvas.tag_bind("btn_update", "<Leave>", lambda e: (self.canvas.config(cursor=""), self.canvas.itemconfig(self.item_update, image=self.img_update_normal)))
        self.canvas.tag_bind("btn_update", "<Button-1>", lambda e: self.check_and_update())

        # Button 3: CÀI ĐẶT
        w3, h3 = 125, 42
        self.img_settings_normal = self.make_pill_texture(w3, h3, (10, 15, 25, 160), (255, 255, 255, 200), 1, "CÀI ĐẶT", (255, 255, 255, 255))
        self.img_settings_hover = self.make_pill_texture(w3, h3, (255, 255, 255, 40), (255, 255, 255, 255), 2, "CÀI ĐẶT", (255, 255, 255, 255))
        self.btn_settings_pos = (345, 436)
        self.item_settings = self.canvas.create_image(self.btn_settings_pos[0], self.btn_settings_pos[1], anchor="nw", image=self.img_settings_normal, tags="btn_settings")
        self.canvas.tag_bind("btn_settings", "<Enter>", lambda e: (self.canvas.config(cursor="hand2"), self.canvas.itemconfig(self.item_settings, image=self.img_settings_hover)))
        self.canvas.tag_bind("btn_settings", "<Leave>", lambda e: (self.canvas.config(cursor=""), self.canvas.itemconfig(self.item_settings, image=self.img_settings_normal)))
        self.canvas.tag_bind("btn_settings", "<Button-1>", lambda e: self.show_settings_overlay())

    def on_start_drag(self, event):
        if self.is_overlay_open:
            self._drag_start_x = None
            return
        if event.y <= 50:
            self._drag_start_x = event.x
            self._drag_start_y = event.y
        else:
            self._drag_start_x = None

    def on_drag_motion(self, event):
        if getattr(self, "_drag_start_x", None) is not None:
            dx = event.x - self._drag_start_x
            dy = event.y - self._drag_start_y
            x = self.root.winfo_x() + dx
            y = self.root.winfo_y() + dy
            self.root.geometry(f"+{x}+{y}")

    def is_game_running(self):
        if hasattr(self, "game_proc") and self.game_proc and self.game_proc.poll() is None:
            return True

        # Native Windows in-memory process enumeration via Win32 Toolhelp API (Zero subprocesses, 100% no CMD flash)
        if sys.platform == "win32":
            try:
                from ctypes import wintypes
                class _PE32(ctypes.Structure):
                    _fields_ = [
                        ('dwSize', wintypes.DWORD),
                        ('cntUsage', wintypes.DWORD),
                        ('th32ProcessID', wintypes.DWORD),
                        ('th32DefaultHeapID', ctypes.c_size_t),
                        ('th32ModuleID', wintypes.DWORD),
                        ('cntThreads', wintypes.DWORD),
                        ('th32ParentProcessID', wintypes.DWORD),
                        ('pcPriClassBase', ctypes.c_long),
                        ('dwFlags', wintypes.DWORD),
                        ('szExeFile', ctypes.c_char * 260)
                    ]
                hSnap = ctypes.windll.kernel32.CreateToolhelp32Snapshot(0x00000002, 0)
                if hSnap != -1 and hSnap != 0:
                    pe = _PE32()
                    pe.dwSize = ctypes.sizeof(_PE32)
                    found = False
                    if ctypes.windll.kernel32.Process32First(hSnap, ctypes.byref(pe)):
                        while True:
                            name = pe.szExeFile.decode(errors="ignore").lower()
                            if "melonds.exe" in name or "desmume.exe" in name:
                                found = True
                                break
                            if not ctypes.windll.kernel32.Process32Next(hSnap, ctypes.byref(pe)):
                                break
                    ctypes.windll.kernel32.CloseHandle(hSnap)
                    if found:
                        return True
            except Exception:
                pass

        # Fallback with explicit CREATE_NO_WINDOW flag
        try:
            flags = getattr(subprocess, "CREATE_NO_WINDOW", 0x08000000) if sys.platform == "win32" else 0
            proc = subprocess.run(
                ["tasklist", "/NH", "/FO", "CSV"],
                capture_output=True, text=True, errors="ignore", timeout=2,
                creationflags=flags
            )
            out = proc.stdout.lower()
            for emu in ["melonds.exe", "desmume.exe"]:
                if emu in out:
                    return True
        except Exception:
            pass
        return False

    def on_window_focus(self):
        if not self.is_overlay_open:
            self.refresh_all_status()

    def refresh_all_status(self):
        self.ver_data = get_current_version()
        rom_path, _ = self.get_current_rom_info()
        self.detect_and_sync_save(rom_path)
        self.update_status_badge()

    def get_current_rom_info(self):
        if not os.path.exists(ROM_DIR):
            return None, None
        
        cfg_rom = self.ver_data.get("rom_file")
        if cfg_rom:
            p = os.path.join(ROM_DIR, cfg_rom)
            if os.path.exists(p):
                return p, self.ver_data.get("version", "Beta 1.0.6")

        nds_files = [f for f in os.listdir(ROM_DIR) if f.lower().endswith(".nds") and not f.endswith(".part")]
        if not nds_files:
            return None, None
        
        nds_files.sort(reverse=True)
        picked = nds_files[0]
        picked_path = os.path.join(ROM_DIR, picked)
        m = re.search(r"Beta\s*([0-9.]+)", picked, re.I)
        v = f"Beta {m.group(1)}" if m else self.ver_data.get("version", "Beta 1.0.6")
        return picked_path, v

    def check_rom_status(self):
        rom_path, _ = self.get_current_rom_info()
        return rom_path is not None and os.path.exists(rom_path)

    def find_newest_save(self):
        candidates = []
        if os.path.exists(ROM_DIR):
            for f in os.listdir(ROM_DIR):
                if f.lower().endswith(".sav"):
                    candidates.append(os.path.join(ROM_DIR, f))
        if os.path.exists(OLD_SAVE_DIR):
            for f in os.listdir(OLD_SAVE_DIR):
                if f.lower().endswith(".sav"):
                    candidates.append(os.path.join(OLD_SAVE_DIR, f))
        if not candidates:
            return None
        candidates.sort(key=lambda p: os.path.getmtime(p), reverse=True)
        return candidates[0]

    def detect_and_sync_save(self, rom_path=None):
        if not os.path.exists(ROM_DIR):
            self.current_save_info = None
            return None

        if not rom_path:
            rom_path, _ = self.get_current_rom_info()

        if rom_path:
            target_sav = os.path.splitext(rom_path)[0] + ".sav"
        else:
            target_sav = os.path.join(ROM_DIR, "Pokemon Legend Black 2.sav")

        # 1. Migrate any leftover from OLD_SAVE_DIR
        if os.path.exists(OLD_SAVE_DIR):
            for f in os.listdir(OLD_SAVE_DIR):
                if f.lower().endswith(".sav"):
                    src = os.path.join(OLD_SAVE_DIR, f)
                    try:
                        if not os.path.exists(target_sav):
                            shutil.move(src, target_sav)
                        else:
                            os.remove(src)
                    except Exception: pass
            try:
                if not os.listdir(OLD_SAVE_DIR):
                    shutil.rmtree(OLD_SAVE_DIR, ignore_errors=True)
            except Exception: pass

        sav_files = [f for f in os.listdir(ROM_DIR) if f.lower().endswith(".sav")]
        if not sav_files:
            self.current_save_info = None
            return None

        target_name = os.path.basename(target_sav)
        if target_name not in sav_files:
            newest = self.find_newest_save()
            if newest and newest != target_sav:
                try:
                    shutil.copy2(newest, target_sav)
                except Exception: pass

        if os.path.exists(target_sav):
            self.current_save_info = {
                "name": os.path.basename(target_sav),
                "size_kb": os.path.getsize(target_sav) // 1024,
                "time_str": time.strftime("%d/%m/%Y %H:%M", time.localtime(os.path.getmtime(target_sav)))
            }
            return target_sav

        self.current_save_info = None
        return None

    def update_status_badge(self):
        rom_path, v = self.get_current_rom_info()
        has_rom = rom_path is not None and os.path.exists(rom_path)
        rom_txt = "ROM: Sẵn sàng" if has_rom else "ROM: Chưa có"
        save_txt = "Save: Sẵn sàng" if self.current_save_info else "Save: Chưa có"
        ver_display = v if v else self.ver_data.get("version", "Beta 1.0.6")

        txt = f"Phiên bản: {ver_display}   •   {rom_txt}   •   {save_txt}"
        col = "#94A3B8" if has_rom else "#F87171"

        self.canvas.itemconfig(self.status_item, text=txt, fill=col)

    def on_click_play(self):
        if self.is_overlay_open:
            return
        self.launch_game()

    def launch_game(self):
        rom_path, _ = self.get_current_rom_info()

        if not rom_path or not os.path.exists(rom_path):
            self.show_missing_rom_overlay()
            return

        self.detect_and_sync_save(rom_path)

        emu_path = os.path.join(EMU_DIR, "melonDS.exe")
        if not os.path.exists(emu_path):
            alt_emu = os.path.abspath(os.path.join(APP_DIR, "melonDS-1.1-win64", "melonDS.exe"))
            if os.path.exists(alt_emu):
                emu_path = alt_emu
            else:
                messagebox.showerror("Lỗi", f"Không tìm thấy emulator melonDS.exe tại:\n{emu_path}")
                return

        try:
            self.game_proc = subprocess.Popen([emu_path, rom_path])

            def monitor_game(p=self.game_proc, r=rom_path):
                p.wait()
                sav_path = os.path.splitext(r)[0] + ".sav"
                if os.path.exists(sav_path):
                    try:
                        ts = time.strftime("%Y%m%d_%H%M%S")
                        v = self.ver_data.get("version", "game")
                        shutil.copy2(sav_path, os.path.join(BACKUP_DIR, f"save_{v}_{ts}.bak"))
                    except Exception as e:
                        print("Post-game sync backup warning:", e)
                self.root.after(0, self.refresh_all_status)

            threading.Thread(target=monitor_game, daemon=True).start()

        except Exception as e:
            messagebox.showerror("Lỗi khởi chạy", str(e))

    # =========================================================================
    # IN-CANVAS OVERLAY SYSTEM (100% Free of Win32 Freezes or Grabbing Deadlocks)
    # =========================================================================

    def close_overlay(self):
        self.canvas.delete("modal_layer")
        if hasattr(self, "entry_server") and self.entry_server:
            try:
                self.entry_server.destroy()
            except Exception:
                pass
            self.entry_server = None
        self.canvas.config(cursor="")
        self.is_overlay_open = False
        self.refresh_all_status()

    def show_settings_overlay(self):
        self.close_overlay()
        self.is_overlay_open = True

        # 1. Dim background sheet (clicking it closes the modal)
        self.canvas.create_image(0, 0, anchor="nw", image=self.dim_photo, tags="modal_layer")
        self.canvas.tag_bind("modal_layer", "<Button-1>", lambda e: self.close_overlay())

        # Card geometry
        MW, MH = 560, 410
        x1, y1 = (WIN_W - MW) // 2, (WIN_H - MH) // 2
        x2, y2 = x1 + MW, y1 + MH

        # 2. Render Card Background with smooth rounded corners via PIL
        card_img = Image.new("RGBA", (MW, MH), (0, 0, 0, 0))
        c_draw = ImageDraw.Draw(card_img)
        c_draw.rounded_rectangle([(0, 0), (MW - 1, MH - 1)], radius=16, fill=(11, 15, 25, 250), outline=(30, 41, 59, 255), width=2)

        # Card Title
        c_draw.text((36, 24), "CÀI ĐẶT & QUẢN LÝ", font=get_custom_font(20, bold=True), fill=(255, 255, 255, 255))

        # Card Subtitle
        rom_path, v = self.get_current_rom_info()
        ver_display = (v.strip() if v else "") or (self.ver_data.get("version", "").strip()) or "Chưa có ROM"
        if self.current_save_info:
            save_txt = f"{self.current_save_info['name']} ({self.current_save_info['size_kb']} KB)"
        else:
            save_txt = "Chưa có file save trong thư mục Game"
        c_draw.text((36, 54), f"Phiên bản: {ver_display}   •   Save: {save_txt}", font=get_custom_font(12, bold=False), fill=(148, 163, 184, 255))

        # Dividers
        c_draw.line([(36, 78), (MW - 36, 78)], fill=(30, 41, 59, 255), width=1)
        c_draw.line([(36, 260), (MW - 36, 260)], fill=(30, 41, 59, 255), width=1)

        # Server Label
        c_draw.text((36, 276), "MÁY CHỦ CẬP NHẬT:", font=get_custom_font(11, bold=True), fill=(148, 163, 184, 255))

        # Footer Tip
        c_draw.text((36, 358), "Lưu ý: Kéo thả file save (.sav) cũ vào thư mục Game rồi bấm [Quét & Chuẩn Hóa Save]", font=get_custom_font(11, bold=False), fill=(100, 116, 139, 255))

        self.modal_card_photo = ImageTk.PhotoImage(card_img)
        it_card = self.canvas.create_image(x1, y1, anchor="nw", image=self.modal_card_photo, tags="modal_layer")
        # Prevent clicks on card from closing the modal
        self.canvas.tag_bind(it_card, "<Button-1>", lambda e: "break")

        # 3. Pill Buttons inside Modal
        btn_w = 488
        btn_h = 42

        # Button 1: Mở thư mục Game
        img_m_game_n = self.make_pill_texture(btn_w, btn_h, (15, 23, 42, 220), (255, 255, 255, 180), 1, "MỞ THƯ MỤC GAME", (255, 255, 255, 255), bold=True, font_size=13)
        img_m_game_h = self.make_pill_texture(btn_w, btn_h, (255, 255, 255, 40), (255, 255, 255, 255), 2, "MỞ THƯ MỤC GAME", (255, 255, 255, 255), bold=True, font_size=13)
        it_m_game = self.canvas.create_image(x1 + 36, y1 + 95, anchor="nw", image=img_m_game_n, tags="modal_layer")
        self.canvas.tag_bind(it_m_game, "<Enter>", lambda e: (self.canvas.config(cursor="hand2"), self.canvas.itemconfig(it_m_game, image=img_m_game_h)))
        self.canvas.tag_bind(it_m_game, "<Leave>", lambda e: (self.canvas.config(cursor=""), self.canvas.itemconfig(it_m_game, image=img_m_game_n)))
        self.canvas.tag_bind(it_m_game, "<Button-1>", lambda e: self.open_game_folder())

        # Button 2: Quét & Chuẩn Hóa Save
        img_m_sync_n = self.make_pill_texture(btn_w, btn_h, (15, 23, 42, 220), (255, 255, 255, 180), 1, "QUÉT & CHUẨN HÓA SAVE", (255, 255, 255, 255), bold=True, font_size=13)
        img_m_sync_h = self.make_pill_texture(btn_w, btn_h, (255, 255, 255, 40), (255, 255, 255, 255), 2, "QUÉT & CHUẨN HÓA SAVE", (255, 255, 255, 255), bold=True, font_size=13)
        it_m_sync = self.canvas.create_image(x1 + 36, y1 + 148, anchor="nw", image=img_m_sync_n, tags="modal_layer")
        self.canvas.tag_bind(it_m_sync, "<Enter>", lambda e: (self.canvas.config(cursor="hand2"), self.canvas.itemconfig(it_m_sync, image=img_m_sync_h)))
        self.canvas.tag_bind(it_m_sync, "<Leave>", lambda e: (self.canvas.config(cursor=""), self.canvas.itemconfig(it_m_sync, image=img_m_sync_n)))
        self.canvas.tag_bind(it_m_sync, "<Button-1>", lambda e: self.on_click_sync_save())

        # Button 3: Tải lại từ server
        img_m_dl_n = self.make_pill_texture(btn_w, btn_h, (255, 255, 255, 255), None, 0, "TẢI LẠI TỪ SERVER", (0, 0, 0, 255), bold=True, font_size=13)
        img_m_dl_h = self.make_pill_texture(btn_w, btn_h, (235, 240, 250, 255), (56, 189, 248, 255), 1, "TẢI LẠI TỪ SERVER", (0, 0, 0, 255), bold=True, font_size=13)
        it_m_dl = self.canvas.create_image(x1 + 36, y1 + 201, anchor="nw", image=img_m_dl_n, tags="modal_layer")
        self.canvas.tag_bind(it_m_dl, "<Enter>", lambda e: (self.canvas.config(cursor="hand2"), self.canvas.itemconfig(it_m_dl, image=img_m_dl_h)))
        self.canvas.tag_bind(it_m_dl, "<Leave>", lambda e: (self.canvas.config(cursor=""), self.canvas.itemconfig(it_m_dl, image=img_m_dl_n)))
        self.canvas.tag_bind(it_m_dl, "<Button-1>", lambda e: self.trigger_full_download())

        # 4. Open Server Button
        img_m_gh_n = self.make_pill_texture(btn_w, 38, (15, 23, 42, 220), (255, 255, 255, 180), 1, "MỞ SERVER", (255, 255, 255, 255), bold=True, font_size=12)
        img_m_gh_h = self.make_pill_texture(btn_w, 38, (255, 255, 255, 40), (255, 255, 255, 255), 2, "MỞ SERVER", (255, 255, 255, 255), bold=True, font_size=12)
        it_m_gh = self.canvas.create_image(x1 + 36, y1 + 300, anchor="nw", image=img_m_gh_n, tags="modal_layer")
        self.canvas.tag_bind(it_m_gh, "<Enter>", lambda e: (self.canvas.config(cursor="hand2"), self.canvas.itemconfig(it_m_gh, image=img_m_gh_h)))
        self.canvas.tag_bind(it_m_gh, "<Leave>", lambda e: (self.canvas.config(cursor=""), self.canvas.itemconfig(it_m_gh, image=img_m_gh_n)))
        self.canvas.tag_bind(it_m_gh, "<Button-1>", lambda e: webbrowser.open(GITHUB_RELEASES_PAGE))

        # 5. Top Right Close Button (Vector Lines)
        cx, cy = x2 - 28, y1 + 28
        c_l1 = self.canvas.create_line(cx - 7, cy - 7, cx + 7, cy + 7, fill="#94A3B8", width=2, tags="modal_layer")
        c_l2 = self.canvas.create_line(cx + 7, cy - 7, cx - 7, cy + 7, fill="#94A3B8", width=2, tags="modal_layer")
        c_hit = self.canvas.create_rectangle(cx - 15, cy - 15, cx + 15, cy + 15, fill="", outline="", tags="modal_layer")
        self.canvas.tag_bind(c_hit, "<Enter>", lambda e: (self.canvas.itemconfig(c_l1, fill="#EF4444"), self.canvas.itemconfig(c_l2, fill="#EF4444"), self.canvas.config(cursor="hand2")))
        self.canvas.tag_bind(c_hit, "<Leave>", lambda e: (self.canvas.itemconfig(c_l1, fill="#94A3B8"), self.canvas.itemconfig(c_l2, fill="#94A3B8"), self.canvas.config(cursor="")))
        self.canvas.tag_bind(c_hit, "<Button-1>", lambda e: self.close_overlay())

        # Keep Python object references to prevent garbage collection
        self._modal_refs = [
            img_m_game_n, img_m_game_h, img_m_sync_n, img_m_sync_h,
            img_m_dl_n, img_m_dl_h, img_m_gh_n, img_m_gh_h, self.modal_card_photo
        ]

    def open_game_folder(self):
        os.makedirs(ROM_DIR, exist_ok=True)
        try:
            os.startfile(ROM_DIR)
        except Exception as e:
            messagebox.showerror("Lỗi", f"Không thể mở thư mục:\n{e}")

    def on_click_sync_save(self):
        found = self.detect_and_sync_save()
        self.refresh_all_status()
        if found:
            msg = f"Đã quét và đồng bộ Save thành công!\n\nFile save: {self.current_save_info['name']}\nDung lượng: {self.current_save_info['size_kb']} KB"
        else:
            msg = "Chưa phát hiện file save (.sav) nào trong thư mục Game.\nBạn có thể kéo thả file save của mình vào thư mục Game (rom/) rồi quét lại."
        messagebox.showinfo("Đồng bộ Save", msg)
        self.show_settings_overlay()

    def show_missing_rom_overlay(self):
        self.close_overlay()
        self.is_overlay_open = True

        # Dim background sheet
        self.canvas.create_image(0, 0, anchor="nw", image=self.dim_photo, tags="modal_layer")
        self.canvas.tag_bind("modal_layer", "<Button-1>", lambda e: self.close_overlay())

        MW, MH = 520, 310
        x1, y1 = (WIN_W - MW) // 2, (WIN_H - MH) // 2
        x2, y2 = x1 + MW, y1 + MH

        card_img = Image.new("RGBA", (MW, MH), (0, 0, 0, 0))
        c_draw = ImageDraw.Draw(card_img)
        c_draw.rounded_rectangle([(0, 0), (MW - 1, MH - 1)], radius=16, fill=(11, 15, 25, 250), outline=(30, 41, 59, 255), width=2)

        c_draw.text((MW // 2, 38), "CHƯA CÓ GAME", font=get_custom_font(20, bold=True), fill=(239, 68, 68, 255), anchor="mm")
        sub = "Vui lòng tải game trực tiếp từ server hoặc tải rom về thư mục game"
        c_draw.text((MW // 2, 74), sub, font=get_custom_font(12, bold=False), fill=(148, 163, 184, 255), anchor="mm")

        self.missing_card_photo = ImageTk.PhotoImage(card_img)
        it_card = self.canvas.create_image(x1, y1, anchor="nw", image=self.missing_card_photo, tags="modal_layer")
        self.canvas.tag_bind(it_card, "<Button-1>", lambda e: "break")

        btn_w = 440
        btn_h = 42

        # Button 1: Tải game từ server
        img_dl_n = self.make_pill_texture(btn_w, btn_h, (255, 255, 255, 255), None, 0, "TẢI GAME TỪ SERVER", (0, 0, 0, 255), bold=True, font_size=13)
        img_dl_h = self.make_pill_texture(btn_w, btn_h, (235, 240, 250, 255), (56, 189, 248, 255), 1, "TẢI GAME TỪ SERVER", (0, 0, 0, 255), bold=True, font_size=13)
        it_dl = self.canvas.create_image(x1 + 40, y1 + 120, anchor="nw", image=img_dl_n, tags="modal_layer")
        self.canvas.tag_bind(it_dl, "<Enter>", lambda e: (self.canvas.config(cursor="hand2"), self.canvas.itemconfig(it_dl, image=img_dl_h)))
        self.canvas.tag_bind(it_dl, "<Leave>", lambda e: (self.canvas.config(cursor=""), self.canvas.itemconfig(it_dl, image=img_dl_n)))
        self.canvas.tag_bind(it_dl, "<Button-1>", lambda e: self.trigger_full_download())

        # Button 2: Mở thư mục Game
        img_rom_n = self.make_pill_texture(btn_w, btn_h, (15, 23, 42, 220), (255, 255, 255, 180), 1, "MỞ THƯ MỤC GAME", (255, 255, 255, 255), bold=True, font_size=13)
        img_rom_h = self.make_pill_texture(btn_w, btn_h, (255, 255, 255, 40), (255, 255, 255, 255), 2, "MỞ THƯ MỤC GAME", (255, 255, 255, 255), bold=True, font_size=13)
        it_rom = self.canvas.create_image(x1 + 40, y1 + 175, anchor="nw", image=img_rom_n, tags="modal_layer")
        self.canvas.tag_bind(it_rom, "<Enter>", lambda e: (self.canvas.config(cursor="hand2"), self.canvas.itemconfig(it_rom, image=img_rom_h)))
        self.canvas.tag_bind(it_rom, "<Leave>", lambda e: (self.canvas.config(cursor=""), self.canvas.itemconfig(it_rom, image=img_rom_n)))
        self.canvas.tag_bind(it_rom, "<Button-1>", lambda e: self.open_game_folder())

        # Button 3: Đóng
        img_close_n = self.make_pill_texture(btn_w, 36, (10, 15, 25, 140), (148, 163, 184, 140), 1, "ĐÓNG", (148, 163, 184, 255), bold=False, font_size=12)
        img_close_h = self.make_pill_texture(btn_w, 36, (255, 255, 255, 30), (255, 255, 255, 200), 1, "ĐÓNG", (255, 255, 255, 255), bold=False, font_size=12)
        it_close = self.canvas.create_image(x1 + 40, y1 + 235, anchor="nw", image=img_close_n, tags="modal_layer")
        self.canvas.tag_bind(it_close, "<Enter>", lambda e: (self.canvas.config(cursor="hand2"), self.canvas.itemconfig(it_close, image=img_close_h)))
        self.canvas.tag_bind(it_close, "<Leave>", lambda e: (self.canvas.config(cursor=""), self.canvas.itemconfig(it_close, image=img_close_n)))
        self.canvas.tag_bind(it_close, "<Button-1>", lambda e: self.close_overlay())

        cx, cy = x2 - 28, y1 + 28
        c_l1 = self.canvas.create_line(cx - 7, cy - 7, cx + 7, cy + 7, fill="#94A3B8", width=2, tags="modal_layer")
        c_l2 = self.canvas.create_line(cx + 7, cy - 7, cx - 7, cy + 7, fill="#94A3B8", width=2, tags="modal_layer")
        c_hit = self.canvas.create_rectangle(cx - 15, cy - 15, cx + 15, cy + 15, fill="", outline="", tags="modal_layer")
        self.canvas.tag_bind(c_hit, "<Enter>", lambda e: (self.canvas.itemconfig(c_l1, fill="#EF4444"), self.canvas.itemconfig(c_l2, fill="#EF4444"), self.canvas.config(cursor="hand2")))
        self.canvas.tag_bind(c_hit, "<Leave>", lambda e: (self.canvas.itemconfig(c_l1, fill="#94A3B8"), self.canvas.itemconfig(c_l2, fill="#94A3B8"), self.canvas.config(cursor="")))
        self.canvas.tag_bind(c_hit, "<Button-1>", lambda e: self.close_overlay())

        self._missing_refs = [img_dl_n, img_dl_h, img_rom_n, img_rom_h, img_close_n, img_close_h, self.missing_card_photo]

    def show_download_overlay(self):
        self.trigger_full_download()

    def trigger_full_download(self):
        self.close_overlay()
        if self.is_game_running():
            messagebox.showwarning(
                "Trò chơi đang chạy",
                "Trò chơi đang mở!\n\nVui lòng lưu game (In-game Save) và đóng cửa sổ giả lập trước khi tải ROM để bảo toàn dữ liệu save."
            )
            return

        self.canvas.itemconfig(self.status_item, text="Đang lấy thông tin bản mới nhất từ server...", fill="#FBBF24")
        self.root.update()

        try:
            req = urllib.request.Request(GITHUB_API_LATEST, headers={
                "User-Agent": APP_USER_AGENT,
                "Accept": "application/vnd.github.v3+json"
            })
            with urllib.request.urlopen(req, timeout=10) as resp:
                remote_data = json.loads(resp.read().decode("utf-8"))

            tag_name = remote_data.get("tag_name", "")
            remote_v = f"Beta {tag_name.lstrip('v')}" if tag_name.startswith("v") else tag_name

            nds_asset = None
            for a in remote_data.get("assets", []):
                if a.get("name", "").lower().endswith(".nds"):
                    nds_asset = a
                    break

            if not nds_asset:
                raise Exception("Không tìm thấy file ROM (.nds) trong bản phát hành trên máy chủ.")

            self.start_unified_download(
                download_url=nds_asset["browser_download_url"],
                remote_version=remote_v,
                file_size=nds_asset.get("size", 362560104)
            )
        except Exception as e:
            messagebox.showerror("Lỗi kết nối", f"Không thể lấy thông tin tải từ máy chủ:\n{e}")
            self.refresh_all_status()

    def start_unified_download(self, download_url, remote_version, file_size):
        if self.is_game_running():
            messagebox.showwarning(
                "Trò chơi đang chạy",
                "Trò chơi đang mở!\n\nVui lòng lưu game và đóng cửa sổ giả lập trước khi tiếp tục."
            )
            return

        self.close_overlay()
        self.is_overlay_open = True
        self.dl_cancelled = False

        # Dim background sheet
        self.canvas.create_image(0, 0, anchor="nw", image=self.dim_photo, tags="modal_layer")

        MW, MH = 520, 240
        x1, y1 = (WIN_W - MW) // 2, (WIN_H - MH) // 2

        card_img = Image.new("RGBA", (MW, MH), (0, 0, 0, 0))
        c_draw = ImageDraw.Draw(card_img)
        c_draw.rounded_rectangle([(0, 0), (MW - 1, MH - 1)], radius=16, fill=(11, 15, 25, 250), outline=(30, 41, 59, 255), width=2)

        c_draw.text((MW // 2, 36), f"ĐANG TẢI ROM {remote_version}...", font=get_custom_font(20, bold=True), fill=(56, 189, 248, 255), anchor="mm")
        c_draw.text((MW // 2, 64), "Nguồn: Máy chủ cập nhật (Tốc độ cao)", font=get_custom_font(12, bold=False), fill=(148, 163, 184, 255), anchor="mm")

        self.dl_card_photo = ImageTk.PhotoImage(card_img)
        self.canvas.create_image(x1, y1, anchor="nw", image=self.dl_card_photo, tags="modal_layer")

        # Progress bar geometry
        pb_x1 = x1 + 45
        pb_y1 = y1 + 95
        pb_w = 430
        pb_h = 16

        self.canvas.create_rectangle(pb_x1, pb_y1, pb_x1 + pb_w, pb_y1 + pb_h, fill="#1E293B", outline="#334155", width=1, tags="modal_layer")
        self.item_pb_fill = self.canvas.create_rectangle(pb_x1, pb_y1, pb_x1, pb_y1 + pb_h, fill="#38BDF8", outline="", tags="modal_layer")

        self.item_dl_stats = self.canvas.create_text(
            x1 + MW // 2, y1 + 130,
            text="Đang kết nối đến máy chủ cập nhật...",
            font=("Segoe UI", 9), fill="#CBD5E1", anchor="center", tags="modal_layer"
        )

        img_cancel_n = self.make_pill_texture(140, 36, (239, 68, 68, 200), None, 0, "HỦY BỎ", (255, 255, 255, 255), bold=True, font_size=12)
        img_cancel_h = self.make_pill_texture(140, 36, (220, 38, 38, 255), None, 0, "HỦY BỎ", (255, 255, 255, 255), bold=True, font_size=12)
        it_cancel = self.canvas.create_image(x1 + (MW - 140) // 2, y1 + 165, anchor="nw", image=img_cancel_n, tags="modal_layer")
        self.canvas.tag_bind(it_cancel, "<Enter>", lambda e: (self.canvas.config(cursor="hand2"), self.canvas.itemconfig(it_cancel, image=img_cancel_h)))
        self.canvas.tag_bind(it_cancel, "<Leave>", lambda e: (self.canvas.config(cursor=""), self.canvas.itemconfig(it_cancel, image=img_cancel_n)))

        def do_cancel():
            self.dl_cancelled = True
            self.close_overlay()

        self.canvas.tag_bind(it_cancel, "<Button-1>", lambda e: do_cancel())
        self._dl_refs = [img_cancel_n, img_cancel_h, self.dl_card_photo]

        target_rom_name = f"Pokemon Legend Black 2 ({remote_version}).nds"
        target_rom_path = os.path.join(ROM_DIR, target_rom_name)
        temp_path = target_rom_path + ".part"

        def worker():
            try:
                os.makedirs(ROM_DIR, exist_ok=True)
                os.makedirs(BACKUP_DIR, exist_ok=True)

                req = urllib.request.Request(download_url, headers={"User-Agent": APP_USER_AGENT})
                start_time = time.time()

                with urllib.request.urlopen(req, timeout=25) as resp:
                    total_size = int(resp.headers.get("Content-Length", file_size or 362560104))
                    downloaded = 0
                    chunk_size = 256 * 1024

                    with open(temp_path, "wb") as f_out:
                        while not self.dl_cancelled:
                            chunk = resp.read(chunk_size)
                            if not chunk:
                                break
                            f_out.write(chunk)
                            downloaded += len(chunk)

                            elapsed = time.time() - start_time
                            speed = (downloaded / (1024 * 1024)) / elapsed if elapsed > 0 else 0
                            pct = (downloaded / total_size) * 100 if total_size > 0 else 0
                            fill_w = int(pb_w * (pct / 100.0))

                            def update_progress(w=fill_w, d=downloaded, t=total_size, s=speed, p=pct):
                                if self.is_overlay_open:
                                    self.canvas.coords(self.item_pb_fill, pb_x1, pb_y1, pb_x1 + w, pb_y1 + pb_h)
                                    self.canvas.itemconfig(
                                        self.item_dl_stats,
                                        text=f"Đã tải: {d / (1024*1024):.1f} MB / {t / (1024*1024):.1f} MB ({p:.1f}%)   •   {s:.2f} MB/s"
                                    )
                            self.root.after(0, update_progress)

                if self.dl_cancelled:
                    if os.path.exists(temp_path):
                        try: os.remove(temp_path)
                        except Exception: pass
                    return

                # --- STEP A: MIGRATE EXISTING SAVE ---
                existing_save = self.find_newest_save()
                if existing_save and os.path.exists(existing_save):
                    ts = time.strftime("%Y%m%d_%H%M%S")
                    backup_file = os.path.join(BACKUP_DIR, f"save_pre_{remote_version}_{ts}.bak")
                    try:
                        shutil.copy2(existing_save, backup_file)
                    except Exception:
                        pass

                    new_sav_name = f"Pokemon Legend Black 2 ({remote_version}).sav"
                    new_sav_path = os.path.join(ROM_DIR, new_sav_name)
                    try:
                        shutil.copy2(existing_save, new_sav_path)
                    except Exception as se:
                        print("Save copy warning:", se)

                # --- STEP B: FINALIZE ROM & REMOVE OBSOLETE ROMS ---
                for f in os.listdir(ROM_DIR):
                    if f.lower().endswith(".nds") and f != target_rom_name and not f.endswith(".part"):
                        old_rom_file = os.path.join(ROM_DIR, f)
                        try:
                            os.remove(old_rom_file)
                        except Exception:
                            pass

                if os.path.exists(target_rom_path):
                    try: os.remove(target_rom_path)
                    except Exception: pass
                os.rename(temp_path, target_rom_path)

                # --- STEP C: UPDATE VERSION.JSON ---
                self.ver_data["version"] = remote_version
                self.ver_data["rom_file"] = target_rom_name
                self.ver_data["last_updated"] = time.strftime("%Y-%m-%d %H:%M:%S")
                set_current_version(self.ver_data)

                def on_done():
                    self.close_overlay()
                    messagebox.showinfo(
                        "Thành công",
                        f"Đã cập nhật lên {remote_version} thành công!\nSave của bạn đã được chuyển sang bản mới và bảo toàn 100%.\nBạn có thể bấm [VÀO GAME] ngay."
                    )
                    self.refresh_all_status()

                self.root.after(0, on_done)

            except Exception as e:
                def on_err(err=e):
                    self.close_overlay()
                    if os.path.exists(temp_path):
                        try: os.remove(temp_path)
                        except Exception: pass
                    messagebox.showerror("Lỗi tải ROM", f"Không thể tải file ROM từ máy chủ:\n{err}")
                    self.refresh_all_status()

                self.root.after(0, on_err)

        threading.Thread(target=worker, daemon=True).start()

    def check_and_update(self):
        if self.is_overlay_open:
            return

        if self.is_game_running():
            messagebox.showwarning(
                "Trò chơi đang chạy",
                "Trò chơi đang mở!\n\nVui lòng lưu game (In-game Save) và đóng cửa sổ giả lập trước khi tiến hành cập nhật để tránh mất dữ liệu save."
            )
            return

        self.canvas.itemconfig(self.status_item, text="Đang kiểm tra bản cập nhật từ máy chủ...", fill="#FBBF24")
        self.root.update()

        try:
            req = urllib.request.Request(GITHUB_API_LATEST, headers={
                "User-Agent": APP_USER_AGENT,
                "Accept": "application/vnd.github.v3+json"
            })
            with urllib.request.urlopen(req, timeout=8) as resp:
                remote_data = json.loads(resp.read().decode("utf-8"))
        except Exception as e:
            messagebox.showerror("Lỗi kết nối", f"Không thể kết nối đến máy chủ cập nhật:\n{e}")
            self.refresh_all_status()
            return

        tag_name = remote_data.get("tag_name", "")
        remote_v = f"Beta {tag_name.lstrip('v')}" if tag_name.startswith("v") else tag_name
        current_rom_path, current_v = self.get_current_rom_info()

        nds_asset = None
        for a in remote_data.get("assets", []):
            if a.get("name", "").lower().endswith(".nds"):
                nds_asset = a
                break

        if not nds_asset:
            messagebox.showerror("Lỗi bản phát hành", f"Không tìm thấy file ROM (.nds) trong bản phát hành {remote_v} trên máy chủ.")
            self.refresh_all_status()
            return

        if current_v == remote_v and current_rom_path and os.path.exists(current_rom_path):
            messagebox.showinfo("Bản mới nhất", f"Bạn đang chơi phiên bản mới nhất ({current_v})!\nKhông có bản cập nhật mới.")
            self.refresh_all_status()
            return

        body = remote_data.get("body", "")
        if "### Tiếng Việt" in body:
            changelog = body.split("### English")[0].replace("### Tiếng Việt", "").strip()
        else:
            changelog = body.strip()
        if not changelog:
            changelog = "Cập nhật tính năng và sửa lỗi."

        ans = messagebox.askyesno(
            "Bản cập nhật mới!",
            f"Phiên bản hiện tại: {current_v or 'Chưa có'}\nPhiên bản mới: {remote_v}\n\nNội dung cập nhật:\n{changelog}\n\nNâng cấp ngay? (Tiến trình save được tự động chuyển sang bản mới 100%)"
        )
        if not ans:
            self.refresh_all_status()
            return

        self.start_unified_download(
            download_url=nds_asset["browser_download_url"],
            remote_version=remote_v,
            file_size=nds_asset.get("size", 362560104)
        )

    def trigger_startup_update_check(self):
        # KHÔNG chạy kiểm tra cập nhật tự động khi chưa có ROM
        rom_path, current_v = self.get_current_rom_info()
        if not rom_path or not os.path.exists(rom_path):
            return

        def bg_check():
            try:
                req = urllib.request.Request(GITHUB_API_LATEST, headers={
                    "User-Agent": APP_USER_AGENT,
                    "Accept": "application/vnd.github.v3+json"
                })
                with urllib.request.urlopen(req, timeout=6) as resp:
                    remote_data = json.loads(resp.read().decode("utf-8"))

                tag_name = remote_data.get("tag_name", "")
                remote_v = f"Beta {tag_name.lstrip('v')}" if tag_name.startswith("v") else tag_name

                nds_asset = None
                for a in remote_data.get("assets", []):
                    if a.get("name", "").lower().endswith(".nds"):
                        nds_asset = a
                        break

                if not nds_asset:
                    return

                # Nếu có bản mới khác với bản đang chơi
                if remote_v and current_v and remote_v != current_v:
                    def notify_user():
                        if self.is_overlay_open or self.is_game_running():
                            return

                        body = remote_data.get("body", "")
                        if "### Tiếng Việt" in body:
                            changelog = body.split("### English")[0].replace("### Tiếng Việt", "").strip()
                        else:
                            changelog = body.strip()
                        if not changelog:
                            changelog = "Cập nhật tính năng và sửa lỗi."

                        ans = messagebox.askyesno(
                            "Phát hiện bản cập nhật mới!",
                            f"Đã có phiên bản mới: {remote_v}\n(Phiên bản hiện tại của bạn: {current_v})\n\nNội dung cập nhật:\n{changelog}\n\nBạn có muốn nâng cấp ngay bây giờ không? (Tiến trình save được tự động chuyển sang bản mới 100%)"
                        )
                        if ans:
                            self.start_unified_download(
                                download_url=nds_asset["browser_download_url"],
                                remote_version=remote_v,
                                file_size=nds_asset.get("size", 362560104)
                            )

                    self.root.after(0, notify_user)

            except Exception:
                pass

        threading.Thread(target=bg_check, daemon=True).start()

if __name__ == "__main__":
    root = tk.Tk()
    app = LauncherGUI(root)
    root.mainloop()
