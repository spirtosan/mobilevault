# Shu PassVault — AES-256-GCM password manager
# Copyright (C) 2026  github.com/spirtosan
#
# This program is free software: you can redistribute it and/or modify
# it under the terms of the GNU General Public License as published by
# the Free Software Foundation, either version 3 of the License, or
# (at your option) any later version.
#
# This program is distributed in the hope that it will be useful,
# but WITHOUT ANY WARRANTY; without even the implied warranty of
# MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
# GNU General Public License for more details.
#
# You should have received a copy of the GNU General Public License
# along with this program.  If not, see <https://www.gnu.org/licenses/>.

import os
import re
import sys
import json
import shutil
import pathlib
import ctypes
import webbrowser
import tkinter as tk
import tkinter.font as tkfont
from tkinter import ttk, messagebox, filedialog
from cryptography.hazmat.primitives.kdf.pbkdf2 import PBKDF2HMAC
from cryptography.hazmat.primitives import hashes
from cryptography.hazmat.primitives.ciphers.aead import AESGCM

if os.name == "nt":
    import ctypes.wintypes

# ── Project URLs ──────────────────────────────────────────────────────────────
GITHUB_URL = "https://github.com/spirtosan/shu-pass-vault"
BMAC_URL   = "https://buymeacoffee.com/spirtosan"


# ── Resource path (works both in dev and as a PyInstaller exe) ────────────────
def _resource_path(name):
    base = getattr(sys, "_MEIPASS", os.path.dirname(os.path.abspath(__file__)))
    return os.path.join(base, name)


# ── Palette ───────────────────────────────────────────────────────────────────
C = {
    "bg_main":    "#1a1a1a",
    "bg_sidebar": "#212121",
    "bg_toolbar": "#252525",
    "bg_tree":    "#1e1e1e",
    "bg_heading": "#2a2a2a",
    "bg_status":  "#161616",
    "bg_panel":   "#2d2d2d",
    "bg_input":   "#333333",
    "bg_hover":   "#3a3a3a",
    "fg_primary":   "#e8e8e8",
    "fg_secondary": "#a0a0a0",
    "fg_muted":     "#606060",
    "fg_heading":   "#c8c8c8",
    "orange":  "#f39c12",
    "blue":    "#2980b9",
    "green":   "#27ae60",
    "red":     "#e74c3c",
    "teal":    "#16a085",
    "steel":   "#2471a3",
    "purple":  "#7d3c98",
    "amber":   "#b7950b",
    "violet":  "#6c3483",
    "nav_bg":     "#1e3a5f",
    "nav_fg":     "#c8ddf0",
    "select_bg":  "#1f6aa5",
    "select_fg":  "#ffffff",
    "sep":        "#404040",
    "col_sep":    "#505050",
}


# ── Utilities ─────────────────────────────────────────────────────────────────
def _darken(hex_color, factor=0.78):
    h = hex_color.lstrip("#")
    r, g, b = (int(h[i:i+2], 16) for i in (0, 2, 4))
    return "#{:02x}{:02x}{:02x}".format(
        int(r * factor), int(g * factor), int(b * factor))


# ── Crypto ────────────────────────────────────────────────────────────────────
MAGIC = b"PVAULT1\x00"
SALT_LEN, NONCE_LEN, KDF_ITERATIONS = 16, 12, 600_000

def derive_key(password, salt):
    kdf = PBKDF2HMAC(algorithm=hashes.SHA256(), length=32,
                     salt=salt, iterations=KDF_ITERATIONS)
    return kdf.derive(password.encode("utf-8"))

def encrypt_vault(password, data):
    salt, nonce = os.urandom(SALT_LEN), os.urandom(NONCE_LEN)
    key = derive_key(password, salt)
    ct = AESGCM(key).encrypt(nonce, json.dumps(data).encode("utf-8"), None)
    return MAGIC + salt + nonce + ct

def decrypt_vault(password, raw):
    if raw[:8] != MAGIC:
        raise ValueError("Invalid vault file.")
    salt, nonce, ct = raw[8:24], raw[24:36], raw[36:]
    key = derive_key(password, salt)
    return json.loads(AESGCM(key).decrypt(nonce, ct, None).decode("utf-8"))


# ── Password Strength ─────────────────────────────────────────────────────────
_S_LABELS = ["Very Weak", "Weak",    "Fair",    "Strong",  "Very Strong"]
_S_COLORS = ["#e74c3c",   "#e67e22", "#f1c40f", "#2ecc71", "#27ae60"]
_S_WIDTHS = [0.14, 0.34, 0.55, 0.77, 1.0]

def score_password(pwd):
    if not pwd:
        return 0, "", C["fg_muted"]
    pts = 0
    if len(pwd) >= 8:                    pts += 1
    if len(pwd) >= 12:                   pts += 1
    if len(pwd) >= 16:                   pts += 1
    if re.search(r'[A-Z]', pwd):        pts += 1
    if re.search(r'[a-z]', pwd):        pts += 1
    if re.search(r'[0-9]', pwd):        pts += 1
    if re.search(r'[^A-Za-z0-9]', pwd): pts += 1
    level = min(4, pts * 4 // 7)
    return level, _S_LABELS[level], _S_COLORS[level]


class StrengthMeter(tk.Frame):
    def __init__(self, parent, pwd_var, bg=None, **kw):
        bg = bg or C["bg_panel"]
        super().__init__(parent, bg=bg, **kw)
        track = tk.Frame(self, bg="#3a3a3a", height=5)
        track.pack(fill="x", pady=(4, 2))
        self._fill = tk.Frame(track, bg="#3a3a3a", height=5)
        self._fill.place(relx=0, rely=0, relheight=1, relwidth=0)
        self._lbl = tk.Label(self, text="", font=("Segoe UI", 8),
                             fg=C["fg_muted"], bg=bg, anchor="w")
        self._lbl.pack(fill="x")
        pwd_var.trace_add("write", lambda *_: self._update(pwd_var.get()))

    def _update(self, pwd):
        level, label, color = score_password(pwd)
        self._fill.place_configure(relwidth=_S_WIDTHS[level] if pwd else 0)
        self._fill.config(bg=color if pwd else "#3a3a3a")
        self._lbl.config(text=label, fg=color if pwd else C["fg_muted"])


# ── Rounded Button ────────────────────────────────────────────────────────────
class RoundedButton(tk.Canvas):
    """Canvas-based button with smooth rounded corners.
    Supports pack(fill='x') — redraws automatically when resized.
    """

    def __init__(self, parent, text, command=None,
                 bg="#333333", fg="white", hover_bg=None,
                 font_spec=("Segoe UI", 9), bold=False,
                 radius=9, padx=14, pady=7, **kw):

        weight = "bold" if bold else "normal"
        family, size = font_spec[0], font_spec[1]
        self._fnt = tkfont.Font(family=family, size=size, weight=weight)

        tw = self._fnt.measure(text)
        th = self._fnt.metrics("linespace")
        nat_w = tw + padx * 2
        nat_h = th + pady * 2

        try:
            parent_bg = parent.cget("bg")
        except Exception:
            parent_bg = C["bg_main"]

        super().__init__(parent,
                         width=nat_w, height=nat_h,
                         highlightthickness=0, bd=0,
                         bg=parent_bg, cursor="hand2", **kw)

        self._text   = text
        self._clr    = bg
        self._hclr   = hover_bg or _darken(bg, 0.82)
        self._aclr   = _darken(bg, 0.62)
        self._fg     = fg
        self._r      = radius
        self._cmd    = command
        self._s      = "normal"

        self.bind("<Configure>",       lambda e: self._draw(e.width, e.height))
        self.bind("<Enter>",           lambda e: self._state("hover"))
        self.bind("<Leave>",           lambda e: self._state("normal"))
        self.bind("<Button-1>",        lambda e: self._state("active"))
        self.bind("<ButtonRelease-1>", self._release)

    def _state(self, s):
        self._s = s
        self._draw()

    def _release(self, _):
        self._state("hover")
        if self._cmd:
            self.after(0, self._cmd)

    def _draw(self, w=None, h=None):
        w = w or self.winfo_width()
        h = h or self.winfo_height()
        if w < 4 or h < 4:
            return
        self.delete("all")
        bg = {"normal": self._clr, "hover": self._hclr,
              "active": self._aclr}.get(self._s, self._clr)
        r = min(self._r, h // 2, w // 2)
        self.create_arc(0,     0,     r*2,   r*2,   start=90,  extent=90, fill=bg, outline=bg)
        self.create_arc(w-r*2, 0,     w,     r*2,   start=0,   extent=90, fill=bg, outline=bg)
        self.create_arc(0,     h-r*2, r*2,   h,     start=180, extent=90, fill=bg, outline=bg)
        self.create_arc(w-r*2, h-r*2, w,     h,     start=270, extent=90, fill=bg, outline=bg)
        self.create_rectangle(r, 0,   w-r, h,   fill=bg, outline=bg)
        self.create_rectangle(0, r,   w,   h-r, fill=bg, outline=bg)
        self.create_text(w // 2, h // 2, text=self._text,
                         fill=self._fg, font=self._fnt, anchor="center")


# ── Shared helpers ────────────────────────────────────────────────────────────
def _center_on(child, parent):
    child.update_idletasks()
    px = parent.winfo_rootx() + parent.winfo_width()  // 2
    py = parent.winfo_rooty() + parent.winfo_height() // 2
    cw = child.winfo_width()  // 2
    ch = child.winfo_height() // 2
    child.geometry(f"+{px - cw}+{py - ch}")


def _dark_entry(parent, textvariable, show="", width=30, mono=False):
    font = ("Consolas", 10) if mono else ("Segoe UI", 10)
    return tk.Entry(parent, textvariable=textvariable, show=show,
                    width=width, font=font,
                    bg=C["bg_input"], fg=C["fg_primary"],
                    insertbackground=C["fg_primary"],
                    relief="flat", highlightthickness=1,
                    highlightbackground=C["sep"],
                    highlightcolor=C["blue"])


# ── Master Password Dialog ────────────────────────────────────────────────────
class PasswordDialog(tk.Toplevel):

    def __init__(self, parent, title, prompt, confirm=False):
        super().__init__(parent)
        self.title(title)
        self.resizable(False, False)
        self.result = None
        self.grab_set()
        self.configure(bg=C["bg_panel"])

        tk.Label(self, text=prompt, font=("Segoe UI", 10),
                 fg=C["fg_primary"], bg=C["bg_panel"]).pack(
                     padx=26, pady=(20, 6), anchor="w")

        self._pwd_var  = tk.StringVar()
        self._show_pwd = False

        row = tk.Frame(self, bg=C["bg_panel"])
        row.pack(padx=26, fill="x")
        self._pwd_entry = _dark_entry(row, self._pwd_var, show="•", width=28, mono=True)
        self._pwd_entry.pack(side="left", fill="x", expand=True)
        RoundedButton(row, "👁", command=self._toggle_eye,
                      bg=C["bg_hover"], fg=C["fg_secondary"],
                      font_spec=("Segoe UI", 9),
                      padx=8, pady=5, radius=6).pack(side="left", padx=(6, 0))

        self._caps_lbl = tk.Label(self, text="", fg=C["red"],
                                  font=("Segoe UI", 8), bg=C["bg_panel"])
        self._caps_lbl.pack(pady=(4, 0))
        self._pwd_entry.bind("<KeyRelease>", self._check_caps)

        self._conf_var = None
        if confirm:
            StrengthMeter(self, self._pwd_var, bg=C["bg_panel"]).pack(
                fill="x", padx=26, pady=(6, 10))
            tk.Label(self, text="Confirm password:", font=("Segoe UI", 9),
                     fg=C["fg_secondary"], bg=C["bg_panel"]).pack(padx=26, anchor="w")
            self._conf_var = tk.StringVar()
            _dark_entry(self, self._conf_var, show="•", width=28, mono=True).pack(
                padx=26, pady=(4, 0), fill="x")

        RoundedButton(self, "Unlock / Create", command=self._ok,
                      bg=C["orange"], fg="#1a1a1a",
                      font_spec=("Segoe UI", 9), bold=True,
                      padx=18, pady=9, radius=9).pack(pady=20)

        self.bind("<Return>", lambda e: self._ok())
        self.bind("<Escape>", lambda e: self.destroy())
        self.after(50, self._focus)

    def _focus(self):
        self.focus_force()
        self._pwd_entry.focus_set()

    def _toggle_eye(self):
        self._show_pwd = not self._show_pwd
        self._pwd_entry.config(show="" if self._show_pwd else "•")

    def _check_caps(self, _=None):
        if os.name != "nt":
            return
        try:
            on = bool(ctypes.WinDLL("user32").GetKeyState(0x14) & 0x0001)
            self._caps_lbl.config(text="⚠  CAPS LOCK IS ON" if on else "")
        except Exception:
            pass

    def _ok(self):
        pwd = self._pwd_var.get()
        if not pwd:
            return
        if self._conf_var is not None and pwd != self._conf_var.get():
            messagebox.showerror("Mismatch", "Passwords do not match.", parent=self)
            return
        self.result = pwd
        self.destroy()


# ── Entry Dialog ──────────────────────────────────────────────────────────────
class EntryDialog(tk.Toplevel):
    """Resizable popup — Enter saves, Escape cancels."""

    def __init__(self, parent, entry=None):
        super().__init__(parent)
        is_edit = entry is not None
        self.title("Edit Entry" if is_edit else "Add Entry")
        self.resizable(True, True)
        self.minsize(480, 400)
        self.geometry("560x470")
        self.result = None
        self.grab_set()
        self.configure(bg=C["bg_panel"])

        self._var_s   = tk.StringVar(value=(entry or {}).get('s',   ''))
        self._var_u   = tk.StringVar(value=(entry or {}).get('u',   ''))
        self._var_p   = tk.StringVar(value=(entry or {}).get('p',   ''))
        self._var_url = tk.StringVar(value=(entry or {}).get('url', ''))
        self._show_pwd    = False
        self._first_entry = None

        body = tk.Frame(self, bg=C["bg_panel"])
        body.pack(fill="both", expand=True, padx=26, pady=(18, 0))
        body.columnconfigure(0, weight=1)

        def field(row_idx, label, var, mono=False, eye=False, strength=False):
            tk.Label(body, text=label, font=("Segoe UI", 8),
                     fg=C["fg_secondary"], bg=C["bg_panel"],
                     anchor="w").grid(row=row_idx * 3, column=0,
                                      sticky="w", pady=(14, 2))
            inner = tk.Frame(body, bg=C["bg_panel"])
            inner.grid(row=row_idx * 3 + 1, column=0, sticky="ew")
            inner.columnconfigure(0, weight=1)
            e = _dark_entry(inner, var, show="•" if eye else "", mono=mono)
            e.grid(row=0, column=0, sticky="ew")
            if self._first_entry is None:
                self._first_entry = e
            if eye:
                self._pwd_entry = e
                RoundedButton(inner, "👁", command=self._toggle_eye,
                              bg=C["bg_hover"], fg=C["fg_secondary"],
                              font_spec=("Segoe UI", 9),
                              padx=8, pady=5, radius=6).grid(
                                  row=0, column=1, padx=(6, 0))
            if strength:
                StrengthMeter(body, var, bg=C["bg_panel"]).grid(
                    row=row_idx * 3 + 2, column=0, sticky="ew", pady=(3, 0))

        field(0, "Service *",         self._var_s)
        field(1, "Username / Email",  self._var_u)
        field(2, "Password *",        self._var_p, mono=True, eye=True, strength=True)
        field(3, "URL (Optional)",    self._var_url)

        btn_row = tk.Frame(self, bg=C["bg_panel"])
        btn_row.pack(fill="x", padx=26, pady=18, side="bottom")

        RoundedButton(btn_row,
                      "Update Entry" if is_edit else "Save Entry",
                      command=self._save,
                      bg=C["green"], fg="white",
                      font_spec=("Segoe UI", 9), bold=True,
                      padx=16, pady=8, radius=9).pack(side="right")

        RoundedButton(btn_row, "Cancel", command=self.destroy,
                      bg=C["bg_hover"], fg=C["fg_secondary"],
                      font_spec=("Segoe UI", 9),
                      padx=14, pady=8, radius=9).pack(side="right", padx=(0, 8))

        self.bind("<Return>", lambda e: self._save())
        self.bind("<Escape>", lambda e: self.destroy())
        self.after(50, self._focus)
        _center_on(self, parent)

    def _focus(self):
        self.focus_force()
        if self._first_entry:
            self._first_entry.focus_set()

    def _toggle_eye(self):
        self._show_pwd = not self._show_pwd
        self._pwd_entry.config(show="" if self._show_pwd else "•")

    def _save(self):
        data = {
            's':   self._var_s.get().strip(),
            'u':   self._var_u.get().strip(),
            'p':   self._var_p.get().strip(),
            'url': self._var_url.get().strip(),
        }
        if not data['s'] or not data['p']:
            messagebox.showwarning("Missing fields",
                                   "Service and Password are required.", parent=self)
            return
        self.result = data
        self.destroy()


# ── Main Application ──────────────────────────────────────────────────────────
class ShuPassVault(tk.Tk):

    def __init__(self):
        super().__init__()
        self.withdraw()
        self._apply_styles()

        self.title("Shu PassVault v1.3")
        self.geometry("1080x660")
        self.minsize(860, 500)
        self.configure(bg=C["bg_main"])

        self._vault_path    = None
        self._password      = None
        self._entries       = []
        self._idle_timer    = None
        self._lock_timeout  = 300_000
        self._pwd_visible   = {}
        self._sep_frames    = []
        self._drag_x        = 0
        self._drag_y        = 0
        self._maximized     = False
        self._prev_geometry = None
        self._rx = self._ry = self._rw = self._rh = 0
        self._rox = self._roy = 0
        self._resize_tag    = None
        self._clip_timer    = None   # clipboard auto-clear handle
        self._sort_state    = {}     # {col: True=ascending}
        self._search_var    = None   # set in _refresh

        self._load_config()

        self._build_titlebar()
        self._build_sidebar()
        self._build_main_area()

        self.deiconify()
        self.update()
        self.after(150, self._strip_frame)
        self.bind_all("<Any-KeyPress>",    self._reset_timer)
        self.bind_all("<Any-ButtonPress>", self._reset_timer)
        self.protocol("WM_DELETE_WINDOW", self.destroy)

    # ── Config ────────────────────────────────────────────────────────────────
    def _load_config(self):
        cfg_dir = pathlib.Path(
            os.environ.get("APPDATA", str(pathlib.Path.home()))
        ) / "ShuPassVault"
        cfg_dir.mkdir(parents=True, exist_ok=True)
        self._cfg_path = cfg_dir / "config.json"
        try:
            self._cfg = json.loads(
                self._cfg_path.read_text(encoding="utf-8"))
        except Exception:
            self._cfg = {"clipboard_timeout": 30}

    def _save_config(self):
        try:
            self._cfg_path.write_text(
                json.dumps(self._cfg, indent=2), encoding="utf-8")
        except Exception:
            pass

    # ── Styles ────────────────────────────────────────────────────────────────
    def _apply_styles(self):
        style = ttk.Style(self)
        style.theme_use("clam")
        style.configure("Treeview",
                        font=("Segoe UI", 10), rowheight=28,
                        background=C["bg_tree"], fieldbackground=C["bg_tree"],
                        foreground=C["fg_primary"], borderwidth=0)
        style.configure("Treeview.Heading",
                        font=("Segoe UI", 9, "bold"),
                        background=C["bg_heading"], foreground=C["fg_heading"],
                        relief="flat", borderwidth=0)
        style.map("Treeview",
                  background=[("selected", C["select_bg"])],
                  foreground=[("selected", C["select_fg"])])
        style.map("Treeview.Heading",
                  background=[("active", C["bg_hover"])])
        style.configure("Vertical.TScrollbar",
                        background=C["bg_hover"], troughcolor=C["bg_main"],
                        bordercolor=C["bg_main"], arrowcolor=C["fg_secondary"],
                        relief="flat")
        style.map("Vertical.TScrollbar",
                  background=[("active", C["fg_muted"])])

    # ── Custom title bar ──────────────────────────────────────────────────────
    def _build_titlebar(self):
        """Custom dark title bar — Win32 chrome stripped in _strip_frame."""
        TB_BG = "#141414"

        bar = tk.Frame(self, bg=TB_BG, height=34)
        bar.pack(fill="x", side="top")
        bar.pack_propagate(False)

        try:
            _ico  = tk.PhotoImage(file=_resource_path("icon.png"))
            scale = max(1, _ico.width() // 18)
            _ico  = _ico.subsample(scale, scale)
            il    = tk.Label(bar, image=_ico, bg=TB_BG)
            il.image = _ico
            il.pack(side="left", padx=(8, 3))
        except Exception:
            pass

        self._title_label = tk.Label(
            bar, text="Shu PassVault  v1.3",
            fg=C["fg_secondary"], bg=TB_BG,
            font=("Segoe UI", 9))
        self._title_label.pack(side="left", padx=(2, 10))

        # Menus created lazily on each click to avoid stale widget refs
        def _menu_popup(label_text, menu_factory):
            lbl = tk.Label(bar, text=label_text,
                           fg=C["fg_secondary"], bg=TB_BG,
                           font=("Segoe UI", 9), padx=8, cursor="hand2")
            lbl.pack(side="left")
            lbl.bind("<Enter>",    lambda e: lbl.config(bg=C["bg_hover"]))
            lbl.bind("<Leave>",    lambda e: lbl.config(bg=TB_BG))
            lbl.bind("<Button-1>", lambda e, mf=menu_factory, l=lbl:
                     mf().tk_popup(l.winfo_rootx(),
                                   l.winfo_rooty() + l.winfo_height()))

        def _vault_menu():
            m = tk.Menu(self, tearoff=0,
                        bg=C["bg_panel"], fg=C["fg_primary"],
                        activebackground=C["blue"], activeforeground="white")
            m.add_command(label="Change Master Password…",
                          command=self._change_password)
            m.add_separator()
            m.add_command(label="Settings…", command=self._show_settings)
            return m

        def _help_menu():
            m = tk.Menu(self, tearoff=0,
                        bg=C["bg_panel"], fg=C["fg_primary"],
                        activebackground=C["blue"], activeforeground="white")
            m.add_command(label="How to Work", command=self._show_help)
            m.add_command(label="About",       command=self._show_about)
            return m

        _menu_popup("Vault", _vault_menu)
        _menu_popup("Help",  _help_menu)

        def _ctrl_btn(symbol, hover_bg, cmd):
            lbl = tk.Label(bar, text=symbol,
                           fg=C["fg_secondary"], bg=TB_BG,
                           font=("Segoe UI", 10), width=4,
                           cursor="hand2")
            lbl.pack(side="right")
            lbl.bind("<Enter>",    lambda e, h=hover_bg: lbl.config(bg=h,     fg="white"))
            lbl.bind("<Leave>",    lambda e:              lbl.config(bg=TB_BG, fg=C["fg_secondary"]))
            lbl.bind("<Button-1>", lambda e: cmd())
            return lbl

        _ctrl_btn("✕", C["red"],     self.destroy)
        self._max_btn = _ctrl_btn("□", C["bg_hover"], self._toggle_maximize)
        _ctrl_btn("─", C["bg_hover"], self._minimize)

        for w in (bar, self._title_label):
            w.bind("<ButtonPress-1>",   self._drag_start)
            w.bind("<B1-Motion>",       self._drag_move)
            w.bind("<Double-Button-1>", lambda e: self._toggle_maximize())

    # ── Window management ─────────────────────────────────────────────────────
    def _drag_start(self, event):
        if not self._maximized:
            self._drag_x = event.x_root - self.winfo_x()
            self._drag_y = event.y_root - self.winfo_y()

    def _drag_move(self, event):
        if not self._maximized:
            self.geometry(
                f"+{event.x_root - self._drag_x}+{event.y_root - self._drag_y}")

    def _minimize(self):
        self.iconify()

    def _toggle_maximize(self):
        if self._maximized:
            self.geometry(self._prev_geometry)
            self._maximized = False
            self._max_btn.config(text="□")
        else:
            self._prev_geometry = self.geometry()
            if os.name == "nt":
                try:
                    # SPI_GETWORKAREA=48 — true desktop area excluding taskbar
                    rc = ctypes.wintypes.RECT()
                    ctypes.windll.user32.SystemParametersInfoW(
                        48, 0, ctypes.byref(rc), 0)
                    w = rc.right  - rc.left
                    h = rc.bottom - rc.top
                    x, y = rc.left, rc.top
                except Exception:
                    w = self.winfo_screenwidth()
                    h = self.winfo_screenheight()
                    x = y = 0
            else:
                w = self.winfo_screenwidth()
                h = self.winfo_screenheight()
                x = y = 0
            self.geometry(f"{w}x{h}+{x}+{y}")
            self._maximized = True
            self._max_btn.config(text="❐")

    def _strip_frame(self):
        """Remove native chrome via SetWindowLong without overrideredirect.

        Targets the outer wrapper HWND — GetParent(winfo_id()) — which owns
        WS_CAPTION and WS_THICKFRAME.  The window stays registered with the
        shell so the taskbar button and Alt+Tab entry work correctly.
        Skipped silently on non-Windows platforms.
        """
        if os.name != "nt":
            self._outer_hwnd = None
            self._add_resize_handles()
            return
        user32 = ctypes.windll.user32
        try:
            inner = self.winfo_id()
            outer = user32.GetParent(inner)
            hwnd  = outer if outer else inner
            self._outer_hwnd = hwnd

            GWL_STYLE        = -16
            GWL_EXSTYLE      = -20
            WS_CAPTION       = 0x00C00000
            WS_THICKFRAME    = 0x00040000
            # WS_EX_APPWINDOW (0x00040000) and WS_THICKFRAME share the same
            # hex value but apply to separate style registers — no conflict.
            WS_EX_APPWINDOW  = 0x00040000
            WS_EX_WINDOWEDGE = 0x00000100

            style = user32.GetWindowLongW(hwnd, GWL_STYLE)
            style &= ~(WS_CAPTION | WS_THICKFRAME)
            user32.SetWindowLongW(hwnd, GWL_STYLE, style)

            ex = user32.GetWindowLongW(hwnd, GWL_EXSTYLE)
            ex = (ex & ~WS_EX_WINDOWEDGE) | WS_EX_APPWINDOW
            user32.SetWindowLongW(hwnd, GWL_EXSTYLE, ex)

            # SWP_NOMOVE | SWP_NOSIZE | SWP_NOZORDER | SWP_FRAMECHANGED
            user32.SetWindowPos(hwnd, 0, 0, 0, 0, 0, 0x0027)
        except Exception:
            self._outer_hwnd = self.winfo_id()

        self._apply_dwm()
        self._add_resize_handles()
        self._load_icon()

    def _load_icon(self):
        """Set icon.ico on the HWND (Windows only)."""
        if os.name != "nt":
            return
        try:
            hwnd            = getattr(self, "_outer_hwnd", None) or self.winfo_id()
            ico_path        = _resource_path("icon.ico")
            IMAGE_ICON      = 1
            LR_LOADFROMFILE = 0x00000010
            LR_DEFAULTSIZE  = 0x00000040
            WM_SETICON      = 0x0080

            hicon_big = ctypes.windll.user32.LoadImageW(
                None, ico_path, IMAGE_ICON,
                0, 0, LR_LOADFROMFILE | LR_DEFAULTSIZE)
            hicon_small = ctypes.windll.user32.LoadImageW(
                None, ico_path, IMAGE_ICON,
                16, 16, LR_LOADFROMFILE)

            if hicon_big:
                ctypes.windll.user32.SendMessageW(hwnd, WM_SETICON, 1, hicon_big)
            if hicon_small:
                ctypes.windll.user32.SendMessageW(hwnd, WM_SETICON, 0, hicon_small)
        except Exception:
            pass

    def _apply_dwm(self):
        """Windows 11 rounded corners + drop shadow via DWM (Windows only)."""
        if os.name != "nt":
            return
        try:
            hwnd = getattr(self, "_outer_hwnd", None)
            if not hwnd:
                hwnd = ctypes.windll.user32.GetParent(self.winfo_id()) or self.winfo_id()
            DWMWA_WINDOW_CORNER_PREFERENCE = 33
            DWMWCP_ROUND = 2
            pref = ctypes.c_int(DWMWCP_ROUND)
            ctypes.windll.dwmapi.DwmSetWindowAttribute(
                hwnd, DWMWA_WINDOW_CORNER_PREFERENCE,
                ctypes.byref(pref), ctypes.sizeof(pref))
        except Exception:
            pass

    def _add_resize_handles(self):
        """Transparent overlay frames on all 8 resize zones."""
        B = 6
        zones = [
            ("n",  "size_ns",    dict(relx=0, rely=0,  relwidth=1,  height=B)),
            ("s",  "size_ns",    dict(relx=0, rely=1,  relwidth=1,  height=B,   y=-B)),
            ("w",  "size_we",    dict(relx=0, rely=0,  width=B,     relheight=1)),
            ("e",  "size_we",    dict(relx=1, rely=0,  width=B,     relheight=1, x=-B)),
            ("nw", "size_nw_se", dict(x=0,    y=0,     width=B*2,   height=B*2)),
            ("ne", "size_ne_sw", dict(relx=1, y=0,     width=B*2,   height=B*2,  x=-B*2)),
            ("sw", "size_ne_sw", dict(x=0,    rely=1,  width=B*2,   height=B*2,  y=-B*2)),
            ("se", "size_nw_se", dict(relx=1, rely=1,  width=B*2,   height=B*2,  x=-B*2, y=-B*2)),
        ]
        for tag, cursor, pkw in zones:
            f = tk.Frame(self, bg=C["bg_main"], cursor=cursor,
                         bd=0, highlightthickness=0)
            f.place(**pkw)
            f.lift()
            f.bind("<ButtonPress-1>", lambda e, t=tag: self._edge_start(e, t))
            f.bind("<B1-Motion>",     self._edge_drag)

    def _edge_start(self, event, tag):
        self._resize_tag = tag
        self._rx  = event.x_root
        self._ry  = event.y_root
        self._rw  = self.winfo_width()
        self._rh  = self.winfo_height()
        self._rox = self.winfo_x()
        self._roy = self.winfo_y()

    def _edge_drag(self, event):
        if not self._resize_tag:
            return
        dx = event.x_root - self._rx
        dy = event.y_root - self._ry
        t  = self._resize_tag
        x, y, w, h = self._rox, self._roy, self._rw, self._rh
        if "e" in t: w = max(860, self._rw + dx)
        if "s" in t: h = max(500, self._rh + dy)
        if "w" in t:
            w = max(860, self._rw - dx)
            x = self._rox + self._rw - w
        if "n" in t:
            h = max(500, self._rh - dy)
            y = self._roy + self._rh - h
        self.geometry(f"{w}x{h}+{x}+{y}")

    # ── Sidebar ───────────────────────────────────────────────────────────────
    def _build_sidebar(self):
        sb = tk.Frame(self, width=200, bg=C["bg_sidebar"])
        sb.pack(side="left", fill="y")
        sb.pack_propagate(False)

        tk.Label(sb, text="SHU\nPASSVAULT",
                 fg=C["orange"], bg=C["bg_sidebar"],
                 font=("Segoe UI", 13, "bold"), justify="center").pack(pady=(24, 30))

        RoundedButton(sb, "📂   Open Vault", command=self._open,
                      bg=C["orange"], fg="#1a1a1a",
                      font_spec=("Segoe UI", 10), bold=True,
                      padx=10, pady=9, radius=9).pack(
                          fill="x", padx=14, pady=(0, 6))

        RoundedButton(sb, "🔒   Lock Vault", command=self._lock,
                      bg=C["red"], fg="white",
                      font_spec=("Segoe UI", 10),
                      padx=10, pady=8, radius=9).pack(
                          fill="x", padx=14)

        tk.Frame(sb, bg=C["bg_sidebar"]).pack(fill="both", expand=True)
        tk.Frame(sb, bg=C["sep"], height=1).pack(fill="x", padx=14, pady=(0, 10))

        RoundedButton(sb, "＋   New Vault", command=self._new,
                      bg=C["violet"], fg="white",
                      font_spec=("Segoe UI", 9),
                      padx=10, pady=8, radius=9).pack(
                          fill="x", padx=14, pady=(0, 18))

    # ── Main area ─────────────────────────────────────────────────────────────
    def _build_main_area(self):
        self._main = tk.Frame(self, bg=C["bg_main"])
        self._main.pack(side="right", fill="both", expand=True)

        self._status_var = tk.StringVar(value="No vault open.")
        sbar = tk.Frame(self._main, bg=C["bg_status"], height=24)
        sbar.pack(side="bottom", fill="x")
        sbar.pack_propagate(False)
        tk.Label(sbar, textvariable=self._status_var,
                 bg=C["bg_status"], fg=C["fg_muted"],
                 font=("Segoe UI", 8), anchor="w").pack(
                     side="left", padx=12, fill="y")

        self._content = tk.Frame(self._main, bg=C["bg_main"])
        self._content.pack(fill="both", expand=True)
        self._show_welcome()

    def _show_welcome(self):
        for w in self._content.winfo_children():
            w.destroy()
        tk.Label(self._content,
                 text="Open a vault or create a new one to get started.",
                 bg=C["bg_main"], fg=C["fg_muted"],
                 font=("Segoe UI", 11)).pack(expand=True)

    # ── Auto-lock ─────────────────────────────────────────────────────────────
    def _reset_timer(self, _=None):
        if self._idle_timer:
            self.after_cancel(self._idle_timer)
        if self._password:
            self._idle_timer = self.after(self._lock_timeout, self._lock)

    def _lock(self):
        self._password    = None
        self._entries     = []
        self._vault_path  = None
        self._pwd_visible = {}
        self._sep_frames  = []
        for w in self._content.winfo_children():
            w.destroy()
        tk.Label(self._content, text="🔒  Vault locked.",
                 bg=C["bg_main"], fg=C["fg_muted"],
                 font=("Segoe UI", 11)).pack(expand=True)
        self._status("No vault open.")

    # ── Help / About / Settings ───────────────────────────────────────────────
    def _show_help(self):
        messagebox.showinfo("How to Work",
            "VAULT\n"
            "• Open Vault  — unlock an existing .vault file.\n"
            "• New Vault   — create a new encrypted vault (sidebar bottom).\n"
            "• Lock Vault  — immediately wipes all data from memory.\n"
            "• Auto-Lock   — vault locks after 5 minutes of inactivity.\n"
            "• Change Master Password — Vault menu.\n\n"
            "ENTRIES\n"
            "• Add Entry   — opens a resizable form (Service & Password required).\n"
            "• Edit Entry  — select a row, then click Edit or double-click it.\n"
            "• Delete      — select a row and click Delete.\n"
            "• Enter saves the form.  Escape cancels without saving.\n\n"
            "COPY & VIEW\n"
            "• Select a row, then use the Copy / Show-Hide toolbar buttons.\n"
            "• Passwords are always masked by default.\n"
            "• Clipboard is cleared automatically after the configured timeout.\n\n"
            "SEARCH\n"
            "• Type in the search box to filter entries in real time.\n\n"
            "SECURITY\n"
            "• AES-256-GCM encryption, 600,000-iteration PBKDF2 key derivation.\n"
            "• No network access. Everything stays on your machine.\n"
            "• Atomic saves — a crash cannot corrupt your vault.\n"
            "• Automatic .vault.bak backup on every save.\n"
            "• The .vault file is safe to back up anywhere."
        )

    def _show_about(self):
        w = tk.Toplevel(self)
        w.title("About — Shu PassVault")
        w.geometry("400x320")
        w.resizable(False, False)
        w.configure(bg=C["bg_panel"])
        _center_on(w, self)

        tk.Label(w,
                 text=("Shu PassVault\nVersion 1.3\n\n"
                       "© 2026  github.com/spirtosan\n\n"
                       "Released under the\n"
                       "GNU General Public License v3\n\n"
                       "AES-256-GCM  •  PBKDF2-600k  •  Atomic saves"),
                 fg=C["fg_primary"], bg=C["bg_panel"],
                 justify="center", font=("Segoe UI", 10)).pack(expand=True, pady=(20, 0))

        btn_row = tk.Frame(w, bg=C["bg_panel"])
        btn_row.pack(pady=(0, 20))

        RoundedButton(btn_row, "☕  Buy Me a Coffee",
                      command=lambda: webbrowser.open(BMAC_URL),
                      bg=C["orange"], fg="#1a1a1a",
                      font_spec=("Segoe UI", 9), bold=True,
                      padx=14, pady=8, radius=9).pack(side="left", padx=(0, 8))

        RoundedButton(btn_row, "⭐  GitHub",
                      command=lambda: webbrowser.open(GITHUB_URL),
                      bg=C["steel"], fg="white",
                      font_spec=("Segoe UI", 9),
                      padx=14, pady=8, radius=9).pack(side="left")

    def _show_settings(self):
        """Settings dialog — configure clipboard auto-clear timeout."""
        w = tk.Toplevel(self)
        w.title("Settings")
        w.geometry("340x180")
        w.resizable(False, False)
        w.configure(bg=C["bg_panel"])
        w.grab_set()
        _center_on(w, self)

        tk.Label(w, text="Clipboard auto-clear timeout",
                 font=("Segoe UI", 10), fg=C["fg_primary"],
                 bg=C["bg_panel"]).pack(pady=(22, 6))

        options = [("15 seconds", 15), ("30 seconds", 30),
                   ("1 minute",   60), ("2 minutes", 120),
                   ("Never",       0)]
        cur     = self._cfg.get("clipboard_timeout", 30)
        labels  = [o[0] for o in options]
        cur_lbl = next((o[0] for o in options if o[1] == cur), "30 seconds")

        var = tk.StringVar(value=cur_lbl)
        om  = tk.OptionMenu(w, var, *labels)
        om.config(bg=C["bg_input"], fg=C["fg_primary"],
                  activebackground=C["blue"], activeforeground="white",
                  highlightthickness=0, relief="flat",
                  font=("Segoe UI", 10))
        om["menu"].config(bg=C["bg_panel"], fg=C["fg_primary"],
                          activebackground=C["blue"],
                          activeforeground="white")
        om.pack(pady=6)

        def _save():
            selected = var.get()
            val = next((o[1] for o in options if o[0] == selected), 30)
            self._cfg["clipboard_timeout"] = val
            self._save_config()
            w.destroy()

        RoundedButton(w, "Save", command=_save,
                      bg=C["green"], fg="white",
                      font_spec=("Segoe UI", 9), bold=True,
                      padx=18, pady=8, radius=9).pack(pady=14)

        w.bind("<Return>", lambda e: _save())
        w.bind("<Escape>", lambda e: w.destroy())

    def _change_password(self):
        if not self._password:
            messagebox.showinfo("No vault", "Open a vault first.")
            return
        d = PasswordDialog(self, "Change Master Password",
                           "New master password:", confirm=True)
        self.wait_window(d)
        if d.result:
            old_password   = self._password
            self._password = d.result          # tentative in-memory update
            try:
                self._save_disk()              # write vault with new key
            except Exception as exc:
                self._password = old_password  # revert — disk write failed
                messagebox.showerror("Error",
                                     f"Could not save vault:\n{exc}\n\n"
                                     "Master password was NOT changed.")
                return
            messagebox.showinfo("Done", "Master password changed and vault saved.")

    # ── Table & toolbar ───────────────────────────────────────────────────────
    def _refresh(self):
        for w in self._content.winfo_children():
            w.destroy()
        self._sep_frames = []

        # ── Search bar ────────────────────────────────────────────────────────
        search_row = tk.Frame(self._content, bg=C["bg_toolbar"])
        search_row.pack(fill="x", padx=8, pady=(6, 0))
        tk.Label(search_row, text="🔍", bg=C["bg_toolbar"],
                 fg=C["fg_muted"], font=("Segoe UI", 10)).pack(
                     side="left", padx=(4, 2))
        self._search_var = tk.StringVar()
        se = _dark_entry(search_row, self._search_var, width=26)
        se.pack(side="left", fill="x", expand=True, pady=5, padx=(0, 8))
        self._search_var.trace_add("write", lambda *_: self._apply_search())

        # ── Toolbar ───────────────────────────────────────────────────────────
        tb = tk.Frame(self._content, bg=C["bg_toolbar"])
        tb.pack(fill="x")

        def tb_add(text, cmd, bg, fg="white", bold=False):
            btn = RoundedButton(tb, text, command=cmd,
                                bg=bg, fg=fg,
                                font_spec=("Segoe UI", 9), bold=bold,
                                padx=11, pady=6, radius=8)
            btn.pack(side="left", padx=(0, 3), pady=7)
            return btn

        tb_add("＋ Add Entry",          self._add_entry,    C["green"],  bold=True)
        tb_add("✏  Edit",               self._edit_entry,   C["blue"])
        tb_add("🗑  Delete",             self._delete_entry, C["red"])
        tk.Frame(tb, bg=C["sep"], width=1).pack(side="left", fill="y", padx=8, pady=7)
        tb_add("📋 Copy Username",       self._copy_username, C["teal"])
        tb_add("🔑 Copy Password",       self._copy_password, C["steel"])
        tb_add("🔗 Copy URL",            self._copy_url,      C["purple"])
        tb_add("👁  Show/Hide Password", self._toggle_vis,    C["amber"], fg="#1a1a1a")

        # ── Tree container ────────────────────────────────────────────────────
        self._tree_wrap = tk.Frame(self._content, bg=C["bg_main"])
        self._tree_wrap.pack(fill="both", expand=True, padx=8, pady=(6, 8))

        self._col_defs = [
            ("s",   "SERVICE",   215),
            ("u",   "USERNAME",  215),
            ("p",   "PASSWORD",  165),
            ("url", "URL",       300),
        ]
        cols = [c[0] for c in self._col_defs]

        self.tree = ttk.Treeview(self._tree_wrap, columns=cols,
                                  show="headings", selectmode="browse")

        for col, heading, width in self._col_defs:
            self.tree.heading(col, text=heading,
                              command=lambda c=col: self._sort_col(c))
            self.tree.column(col, width=width, anchor="w",
                             stretch=(col == "url"), minwidth=80)

        vsb = ttk.Scrollbar(self._tree_wrap, orient="vertical",
                            command=self.tree.yview,
                            style="Vertical.TScrollbar")
        self.tree.configure(yscrollcommand=vsb.set)
        vsb.pack(side="right", fill="y")
        self.tree.pack(side="left", fill="both", expand=True)

        self.tree.tag_configure("odd",  background="#222222")
        self.tree.tag_configure("even", background=C["bg_tree"])

        self._populate_tree()

        self.tree.bind("<Double-1>",      lambda _: self._edit_entry())
        self.tree.bind("<ButtonRelease-1>",
                       lambda e: self.after(60, self._draw_separators))
        self.tree.bind("<Configure>",
                       lambda e: self.after(60, self._draw_separators))
        self.after(150, self._draw_separators)

        self._update_status()
        self._reset_timer()

    def _populate_tree(self, filter_text=""):
        """Insert entries into the Treeview, optionally filtered by text."""
        self.tree.delete(*self.tree.get_children())
        q = filter_text.lower()
        row_idx = 0
        for i, e in enumerate(self._entries):
            if q and not any(q in str(v).lower()
                             for v in (e.get('s', ''), e.get('u', ''),
                                       e.get('url', ''))):
                continue
            iid     = str(i)
            visible = self._pwd_visible.get(iid, False)
            pwd_d   = e['p'] if visible else "•" * min(len(e['p']), 14)
            tag     = "odd" if row_idx % 2 else "even"
            self.tree.insert("", "end", iid=iid, tags=(tag,),
                             values=(e['s'], e['u'], pwd_d, e.get('url', '')))
            row_idx += 1

    def _apply_search(self):
        q = self._search_var.get() if self._search_var else ""
        self._populate_tree(q)

    # ── Column separator lines ────────────────────────────────────────────────
    def _draw_separators(self):
        for s in self._sep_frames:
            try:
                s.destroy()
            except Exception:
                pass
        self._sep_frames = []

        if not hasattr(self, 'tree') or not self.tree.winfo_exists():
            return

        self.tree.update_idletasks()
        x = 0
        for col, _heading, _width in self._col_defs[:-1]:
            x += self.tree.column(col, "width")
            sep = tk.Frame(self._tree_wrap, bg=C["col_sep"], width=2)
            sep.place(in_=self.tree, x=x - 1, y=0, width=2, relheight=1)
            sep.lift()
            self._sep_frames.append(sep)

    # ── Sort — toggles ascending / descending, shows arrow in heading ─────────
    def _sort_col(self, col):
        asc = not self._sort_state.get(col, False)
        self._sort_state = {col: asc}   # clear other cols
        rows = [(self.tree.set(k, col), k) for k in self.tree.get_children()]
        rows.sort(key=lambda x: x[0].lower(), reverse=not asc)
        for idx, (_, k) in enumerate(rows):
            self.tree.move(k, "", idx)
        for c, heading, _ in self._col_defs:
            arrow = (" ▲" if asc else " ▼") if c == col else ""
            self.tree.heading(c, text=heading + arrow)

    # ── Helpers ───────────────────────────────────────────────────────────────
    def _selected_idx(self):
        sel = self.tree.selection()
        return int(sel[0]) if sel else None

    def _status(self, msg):
        self._status_var.set(msg)

    def _update_status(self):
        if self._vault_path:
            name = os.path.basename(self._vault_path)
            self._status(f"Vault: {name}   •   {len(self._entries)} entries   •   "
                         f"AES-256-GCM  •  600k PBKDF2")

    def _flash(self, msg):
        self._title_label.config(text=f"✓  {msg}  —  Shu PassVault")
        self.after(2000, lambda: self._title_label.config(text="Shu PassVault  v1.3"))

    # ── CRUD ──────────────────────────────────────────────────────────────────
    def _add_entry(self):
        d = EntryDialog(self)
        self.wait_window(d)
        if d.result:
            self._entries.append(d.result)
            self._save_disk()
            self._refresh()
            new_iid = str(len(self._entries) - 1)
            self.tree.selection_set(new_iid)
            self.tree.see(new_iid)

    def _edit_entry(self):
        idx = self._selected_idx()
        if idx is None:
            messagebox.showinfo("No selection", "Select an entry to edit.")
            return
        d = EntryDialog(self, self._entries[idx])
        self.wait_window(d)
        if d.result:
            self._entries[idx] = d.result
            self._save_disk()
            self._refresh()
            self.tree.selection_set(str(idx))

    def _delete_entry(self):
        idx = self._selected_idx()
        if idx is None:
            messagebox.showinfo("No selection", "Select an entry to delete.")
            return
        name = self._entries[idx]['s']
        if messagebox.askyesno("Delete",
                               f"Delete '{name}'?\nThis cannot be undone."):
            self._entries.pop(idx)
            self._pwd_visible = {}
            self._save_disk()
            self._refresh()

    def _copy_username(self):
        idx = self._selected_idx()
        if idx is None: return
        val = self._entries[idx].get('u', '')
        if val:
            self.clipboard_clear()
            self.clipboard_append(val)
            self._flash("Username copied.")

    def _copy_password(self):
        idx = self._selected_idx()
        if idx is None: return
        val = self._entries[idx].get('p', '')
        if val:
            self.clipboard_clear()
            self.clipboard_append(val)
            self._schedule_clipboard_clear()
            self._flash("Password copied.")

    def _schedule_clipboard_clear(self):
        """Cancel any pending clear, then start a new countdown."""
        if self._clip_timer:
            self.after_cancel(self._clip_timer)
            self._clip_timer = None
        timeout = self._cfg.get("clipboard_timeout", 30)
        if timeout > 0:
            self._clip_timer = self.after(timeout * 1000, self._clear_clipboard)

    def _clear_clipboard(self):
        """Silently wipe the clipboard contents."""
        try:
            self.clipboard_clear()
            self.clipboard_append("")
        except Exception:
            pass
        self._clip_timer = None

    def _copy_url(self):
        idx = self._selected_idx()
        if idx is None: return
        val = self._entries[idx].get('url', '')
        if val:
            self.clipboard_clear()
            self.clipboard_append(val)
            if val.startswith("http"):
                webbrowser.open(val)
            self._flash("URL copied.")

    def _toggle_vis(self):
        sel = self.tree.selection()
        if not sel: return
        iid = sel[0]
        idx = int(iid)
        self._pwd_visible[iid] = not self._pwd_visible.get(iid, False)
        pwd     = self._entries[idx]['p']
        display = pwd if self._pwd_visible[iid] else "•" * min(len(pwd), 14)
        self.tree.set(iid, column="p", value=display)

    # ── Vault I/O ─────────────────────────────────────────────────────────────
    def _new(self):
        path = filedialog.asksaveasfilename(
            title="Create new vault",
            defaultextension=".vault",
            filetypes=[("PassVault file", "*.vault"), ("All files", "*.*")],
        )
        if not path: return
        d = PasswordDialog(self, "New Vault", "Set master password:", confirm=True)
        self.wait_window(d)
        if d.result:
            self._vault_path  = path
            self._password    = d.result
            self._entries     = []
            self._pwd_visible = {}
            self._save_disk()
            self._refresh()

    def _open(self):
        path = filedialog.askopenfilename(
            title="Open vault",
            filetypes=[("PassVault file", "*.vault"), ("All files", "*.*")],
        )
        if not path: return
        d = PasswordDialog(self, "Unlock Vault", "Enter master password:")
        self.wait_window(d)
        if d.result:
            try:
                with open(path, "rb") as f:
                    raw = f.read()
                data = decrypt_vault(d.result, raw)
                if not isinstance(data, dict) or "entries" not in data:
                    raise ValueError("Unexpected vault structure.")
                self._entries     = data.get("entries", [])
                self._vault_path  = path
                self._password    = d.result
                self._pwd_visible = {}
                self._refresh()
            except Exception:
                messagebox.showerror("Error",
                                     "Wrong password or invalid vault file.")

    def _save_disk(self):
        if not self._vault_path: return
        tmp = self._vault_path + ".tmp"
        with open(tmp, "wb") as f:
            f.write(encrypt_vault(self._password, {"entries": self._entries}))
        os.replace(tmp, self._vault_path)
        # Automatic backup written only after a successful atomic replace
        try:
            shutil.copy2(self._vault_path, self._vault_path + ".bak")
        except Exception:
            pass


if __name__ == "__main__":
    ShuPassVault().mainloop()
