"""Fase 0: comprobar que podemos hablar con la CAMEO 4 vía usbprint.sys.

Uso:
  python spike.py            -> estado + firmware (solo lectura)
  python spike.py --move     -> además mueve el carro 30 mm y vuelve (sin cortar)
"""
import sys
import time

from usbprint import UsbPrintDevice, DeviceTimeout, find_device_paths

ESC, ENQ, EOT, ETX = b"\x1b", b"\x05", b"\x04", b"\x03"


def cmd(dev, s: str | bytes, wait_reply=False, timeout_ms=3000):
    data = s if isinstance(s, bytes) else s.encode("ascii") + ETX
    dev.write(data)
    if not wait_reply:
        return None
    try:
        return dev.read(timeout_ms=timeout_ms)
    except DeviceTimeout:
        return None


def main():
    print("Dispositivos:", find_device_paths())
    with UsbPrintDevice() as dev:
        print("Abierto:", dev.model, dev.path)
        cmd(dev, ESC + EOT)                      # init
        print("ENQ ->", cmd(dev, ESC + ENQ, True))
        print("FG  ->", cmd(dev, "FG", True))
        print("[  ->", cmd(dev, "[", True))      # posición actual
        print("U   ->", cmd(dev, "U", True))     # tamaño de área

        if "--move" in sys.argv:
            print("Moviendo carro 30 mm y volviendo…")
            cmd(dev, "FN0")
            cmd(dev, "M600,600")                 # y=30mm, x=30mm (20 SU/mm)
            time.sleep(1.5)
            cmd(dev, "M0,0")
            cmd(dev, "SO0")
            cmd(dev, "FN0")
            time.sleep(1.5)
            print("ENQ ->", cmd(dev, ESC + ENQ, True))


if __name__ == "__main__":
    main()
