"""
Acceso directo a la Silhouette CAMEO 4 a través de usbprint.sys (driver genérico
de impresoras USB de Windows). No requiere Zadig/libusb: abrimos la interfaz de
dispositivo con CreateFile y hablamos GPGL con WriteFile/ReadFile.

Usamos I/O overlapped para poder aplicar timeouts a la lectura (la máquina no
siempre responde y un ReadFile síncrono quedaría colgado).
"""
from __future__ import annotations

import ctypes
import ctypes.wintypes as wt
import winreg

VENDOR_ID_GRAPHTEC = 0x0B4D
PRODUCT_IDS = {
    0x1137: "Silhouette CAMEO 4",
    0x1138: "Silhouette CAMEO 4 Plus",
    0x1139: "Silhouette CAMEO 4 Pro",
}
GUID_DEVINTERFACE_USBPRINT = "{28d78fad-5a12-11d1-ae5b-0000f803a8c2}"

# --- Win32 ---------------------------------------------------------------
kernel32 = ctypes.WinDLL("kernel32", use_last_error=True)

GENERIC_READ = 0x80000000
GENERIC_WRITE = 0x40000000
FILE_SHARE_READ = 0x1
FILE_SHARE_WRITE = 0x2
OPEN_EXISTING = 3
FILE_FLAG_OVERLAPPED = 0x40000000
INVALID_HANDLE_VALUE = wt.HANDLE(-1).value
ERROR_IO_PENDING = 997
WAIT_OBJECT_0 = 0
WAIT_TIMEOUT = 0x102


class OVERLAPPED(ctypes.Structure):
    _fields_ = [
        ("Internal", ctypes.c_void_p),
        ("InternalHigh", ctypes.c_void_p),
        ("Offset", wt.DWORD),
        ("OffsetHigh", wt.DWORD),
        ("hEvent", wt.HANDLE),
    ]


kernel32.CreateFileW.restype = wt.HANDLE
kernel32.CreateFileW.argtypes = [wt.LPCWSTR, wt.DWORD, wt.DWORD, ctypes.c_void_p,
                                 wt.DWORD, wt.DWORD, wt.HANDLE]
kernel32.WriteFile.argtypes = [wt.HANDLE, ctypes.c_void_p, wt.DWORD,
                               ctypes.POINTER(wt.DWORD), ctypes.POINTER(OVERLAPPED)]
kernel32.ReadFile.argtypes = [wt.HANDLE, ctypes.c_void_p, wt.DWORD,
                              ctypes.POINTER(wt.DWORD), ctypes.POINTER(OVERLAPPED)]
kernel32.GetOverlappedResult.argtypes = [wt.HANDLE, ctypes.POINTER(OVERLAPPED),
                                         ctypes.POINTER(wt.DWORD), wt.BOOL]
kernel32.CreateEventW.restype = wt.HANDLE
kernel32.CreateEventW.argtypes = [ctypes.c_void_p, wt.BOOL, wt.BOOL, wt.LPCWSTR]
kernel32.WaitForSingleObject.argtypes = [wt.HANDLE, wt.DWORD]
kernel32.CancelIoEx.argtypes = [wt.HANDLE, ctypes.POINTER(OVERLAPPED)]
kernel32.CloseHandle.argtypes = [wt.HANDLE]


class DeviceNotFound(Exception):
    pass


class DeviceTimeout(Exception):
    pass


def _win_error(what: str) -> OSError:
    err = ctypes.get_last_error()
    return OSError(err, f"{what}: {ctypes.FormatError(err).strip()}")


def find_device_paths() -> list[tuple[str, str]]:
    """Devuelve [(ruta_interfaz, nombre_modelo)] de las Silhouette conectadas.

    Lee HKLM\\SYSTEM\\CurrentControlSet\\Control\\DeviceClasses\\{usbprint}.
    Cada subclave se llama '##?#USB#VID_xxxx&PID_xxxx#serial#{guid}' y su
    subclave '#' tiene el valor SymbolicLink. Un dispositivo presente tiene
    Control\\Linked = 1.
    """
    base = rf"SYSTEM\CurrentControlSet\Control\DeviceClasses\{GUID_DEVINTERFACE_USBPRINT}"
    found = []
    try:
        root = winreg.OpenKey(winreg.HKEY_LOCAL_MACHINE, base)
    except OSError:
        return found
    with root:
        i = 0
        while True:
            try:
                name = winreg.EnumKey(root, i)
            except OSError:
                break
            i += 1
            upper = name.upper()
            if f"VID_{VENDOR_ID_GRAPHTEC:04X}" not in upper:
                continue
            pid = next((p for p in PRODUCT_IDS if f"PID_{p:04X}" in upper), None)
            if pid is None:
                continue
            try:
                with winreg.OpenKey(root, name + r"\#") as k:
                    link, _ = winreg.QueryValueEx(k, "SymbolicLink")
            except OSError:
                # fallback: construir la ruta desde el nombre de la clave
                link = "\\\\?\\" + name[4:] if name.startswith("##?#") else None
            if not link:
                continue
            linked = 1
            try:
                with winreg.OpenKey(root, name + r"\#\Control") as k:
                    linked, _ = winreg.QueryValueEx(k, "Linked")
            except OSError:
                pass
            if linked:
                found.append((link, PRODUCT_IDS[pid]))
    return found


class UsbPrintDevice:
    """Handle de lectura/escritura sobre la interfaz usbprint de la máquina."""

    def __init__(self, path: str | None = None):
        if path is None:
            devices = find_device_paths()
            if not devices:
                raise DeviceNotFound("No se encontró ninguna Silhouette CAMEO 4 conectada")
            path, self.model = devices[0]
        else:
            self.model = "?"
        self.path = path
        self._h = kernel32.CreateFileW(
            path, GENERIC_READ | GENERIC_WRITE, FILE_SHARE_READ | FILE_SHARE_WRITE,
            None, OPEN_EXISTING, FILE_FLAG_OVERLAPPED, None)
        if self._h == INVALID_HANDLE_VALUE or not self._h:
            raise _win_error(f"CreateFile({path})")

    # -- context manager --------------------------------------------------
    def __enter__(self):
        return self

    def __exit__(self, *exc):
        self.close()

    def close(self):
        if getattr(self, "_h", None):
            kernel32.CloseHandle(self._h)
            self._h = None

    # -- I/O ---------------------------------------------------------------
    def _overlapped_io(self, fn, buf, n: int, timeout_ms: int, what: str) -> int:
        ov = OVERLAPPED()
        ov.hEvent = kernel32.CreateEventW(None, True, False, None)
        if not ov.hEvent:
            raise _win_error("CreateEvent")
        try:
            done = wt.DWORD(0)
            ok = fn(self._h, buf, n, ctypes.byref(done), ctypes.byref(ov))
            if not ok:
                err = ctypes.get_last_error()
                if err != ERROR_IO_PENDING:
                    raise OSError(err, f"{what}: {ctypes.FormatError(err).strip()}")
                rc = kernel32.WaitForSingleObject(ov.hEvent, timeout_ms)
                if rc == WAIT_TIMEOUT:
                    kernel32.CancelIoEx(self._h, ctypes.byref(ov))
                    kernel32.GetOverlappedResult(self._h, ctypes.byref(ov),
                                                 ctypes.byref(done), True)
                    raise DeviceTimeout(f"{what}: sin respuesta en {timeout_ms} ms")
                if not kernel32.GetOverlappedResult(self._h, ctypes.byref(ov),
                                                    ctypes.byref(done), True):
                    raise _win_error(f"GetOverlappedResult({what})")
            return done.value
        finally:
            kernel32.CloseHandle(ov.hEvent)

    def write(self, data: bytes, timeout_ms: int = 5000) -> int:
        buf = ctypes.create_string_buffer(data, len(data))
        total = 0
        while total < len(data):
            chunk = data[total:total + 4096]
            cbuf = ctypes.create_string_buffer(chunk, len(chunk))
            total += self._overlapped_io(kernel32.WriteFile, cbuf, len(chunk),
                                         timeout_ms, "WriteFile")
        return total

    def read(self, size: int = 1024, timeout_ms: int = 2000) -> bytes:
        buf = ctypes.create_string_buffer(size)
        n = self._overlapped_io(kernel32.ReadFile, buf, size, timeout_ms, "ReadFile")
        return buf.raw[:n]
