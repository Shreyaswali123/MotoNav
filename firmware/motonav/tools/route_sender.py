import asyncio
import struct
import zlib

from bleak import BleakScanner, BleakClient
from route_format import build_route

import sys
from pathlib import Path

sys.path.insert(
    0,
    str(Path(__file__).resolve().parent)
)

from route_format import (
    build_route,
    MANEUVER_RIGHT,
    MANEUVER_LEFT,
    MANEUVER_DESTINATION,
)

# IMPORTANT:
# Replace these with the exact characteristic UUIDs shown
# by your ESP32/nRF Connect if necessary.
#
# Based on our current firmware:
CONTROL_UUID = "f1debc9a-7856-3412-5678-123412345678"
STATUS_UUID = "f2debc9a-7856-3412-5678-123412345678"
ROUTE_DATA_UUID = "f3debc9a-7856-3412-5678-123412345678"

DEVICE_NAME = "MotoNav-01"


# ============================================================
# Commands
# ============================================================

CMD_START_ROUTE = 0x01
CMD_END_ROUTE = 0x02
CMD_CANCEL_ROUTE = 0x03


# ============================================================
# Packet types
# ============================================================

PACKET_ROUTE_DATA = 0x03


# ============================================================
# CRC
# ============================================================

def crc32(data: bytes) -> int:
    return zlib.crc32(data) & 0xFFFFFFFF


# ============================================================
# Build ROUTE_DATA packet
# ============================================================

def build_route_packet(sequence: int, payload: bytes) -> bytes:

    header = struct.pack(
        "<BHHB",
        PACKET_ROUTE_DATA,
        sequence,
        len(payload),
        0
    )

    packet_without_crc = header + payload

    crc = crc32(packet_without_crc)

    return (
        packet_without_crc +
        struct.pack("<I", crc)
    )


# ============================================================
# Build START_ROUTE
# ============================================================

def build_start_packet(route_size: int) -> bytes:

    return (
        bytes([CMD_START_ROUTE]) +
        struct.pack("<I", route_size)
    )


# ============================================================
# Build END_ROUTE
# ============================================================

def build_end_packet(route: bytes) -> bytes:

    crc = crc32(route)

    return (
        bytes([CMD_END_ROUTE]) +
        struct.pack("<I", crc)
    )


# ============================================================
# Notification callback
# ============================================================

ack_event = asyncio.Event()
last_ack = None


def status_notification(sender, data: bytearray):

    print(
        "[NOTIFICATION RECEIVED]",
        f"sender={sender}",
        f"HEX={bytes(data).hex(' ').upper()}"
    )

    global last_ack

    try:
        message = data.decode("utf-8")

    except UnicodeDecodeError:
        print(
            f"[STATUS] HEX: "
            f"{data.hex(' ').upper()}"
        )
        return

    print(f"[STATUS] {message}")

    if message.startswith("ACK,"):

        try:
            last_ack = int(
                message.split(",")[1]
            )

            ack_event.set()

        except ValueError:
            pass


# ============================================================
# Wait for ACK
# ============================================================

async def wait_for_ack(sequence: int):

    global last_ack

    last_ack = None
    ack_event.clear()

    try:

        await asyncio.wait_for(
            ack_event.wait(),
            timeout=5
        )

    except asyncio.TimeoutError:

        raise RuntimeError(
            f"Timeout waiting for ACK #{sequence}"
        )

    if last_ack != sequence:

        raise RuntimeError(
            f"Expected ACK #{sequence}, "
            f"received ACK #{last_ack}"
        )

    print(
        f"[OK] ACK #{sequence} received"
    )


# ============================================================
# Main transfer
# ============================================================

async def main():
    global last_ack
    points = [
    (15.3647000, 75.1240000),
    (15.3658000, 75.1262000),
    (15.3681000, 75.1294000),
    (15.3700000, 75.1320000),
    ]

    maneuvers = [
        (1, MANEUVER_RIGHT, 250),
        (2, MANEUVER_LEFT, 180),
        (3, MANEUVER_DESTINATION, 0),
    ]

    route = build_route(
        route_id=1,
        points=points,
        maneuvers=maneuvers
    )

    chunk_size = 7

    print("=" * 60)
    print("MotoNav Automatic BLE Route Sender")
    print("=" * 60)

    print("Route: Binary Route Format v1")
    print(f"Size: {len(route)} bytes")
    print()

    # --------------------------------------------------------
    # Scan
    # --------------------------------------------------------

    print("[1] Scanning for MotoNav-01...")

    device = None

    devices = await BleakScanner.discover(
        timeout=15,
        return_adv=True
    )

    for d, adv in devices.values():

        print(
            f"Found: {d.address} | "
            f"name={d.name} | "
            f"local_name={adv.local_name}"
        )

        # Match by ESP32 MAC first.
        if d.address.upper() == "1C:DB:D4:44:55:C6":
            device = d
            break

        # Also accept the advertised name.
        if (
            d.name == DEVICE_NAME
            or adv.local_name == DEVICE_NAME
        ):
            device = d
            break

    if device is None:
        raise RuntimeError(
            "MotoNav-01 not found"
        )

    print(
        f"[OK] Found: {device.name}"
    )

    print(
        f"    Address: {device.address}"
    )

    # --------------------------------------------------------
    # Connect
    # --------------------------------------------------------

    print()
    print("[2] Connecting...")

    async with BleakClient(device) as client:

        if not client.is_connected:

            raise RuntimeError(
                "BLE connection failed"
            )

        print("[OK] Connected")

        # ----------------------------------------------------
        # DEBUG: Inspect GATT services and characteristics
        # ----------------------------------------------------

        print()
        print("[DEBUG] Discovering GATT services...")

        services = client.services

        for service in services:
            print(
                f"[SERVICE] {service.uuid}"
            )

            for characteristic in service.characteristics:
                print(
                    f"  [CHAR] {characteristic.uuid}"
                )

                print(
                    f"         handle={characteristic.handle}"
                )

                print(
                    f"         properties={characteristic.properties}"
                )

        # ----------------------------------------------------
        # Subscribe to STATUS notifications
        # ----------------------------------------------------

        print()
        print("[3] Testing STATUS read...")

        status_char = client.services.get_characteristic(
            STATUS_UUID
        )

        if status_char is None:
            raise RuntimeError(
                "STATUS characteristic not found"
            )

        print(
            f"[DEBUG] STATUS handle: "
            f"{status_char.handle}"
        )

        status = await client.read_gatt_char(
            status_char
        )

        print(
            "[STATUS READ]",
            status.decode(
                "utf-8",
                errors="replace"
            )
        )

        print("[OK] STATUS read works")

        print()
        print("[3b] Enabling STATUS notifications...")

        await client.start_notify(
            status_char,
            status_notification
        )

        print("[OK] STATUS notifications enabled")

        # ----------------------------------------------------
        # Start route
        # ----------------------------------------------------

        print()
        print("[4] Sending START_ROUTE...")

        start_packet = build_start_packet(
            len(route)
        )

        print(
            "START HEX:",
            start_packet.hex(" ").upper()
        )

        await client.write_gatt_char(
            CONTROL_UUID,
            start_packet,
            response=True
        )

        print("[OK] START_ROUTE sent")

        # ----------------------------------------------------
        # Send route chunks
        # ----------------------------------------------------

        print()
        print("[5] Sending route packets...")

        sequence = 0

        for start in range(
            0,
            len(route),
            chunk_size
        ):

            chunk = route[
                start:start + chunk_size
            ]

            packet = build_route_packet(
                sequence,
                chunk
            )

            print()
            print(
                f"ROUTE_DATA #{sequence}"
            )

            print(
                "Payload HEX:",
                chunk.hex(" ").upper()
            )

            print(
                "HEX:",
                packet.hex(" ").upper()
            )

            last_ack = None
            ack_event.clear()

            await client.write_gatt_char(
                ROUTE_DATA_UUID,
                packet,
                response=True
            )

            try:
                await asyncio.wait_for(
                    ack_event.wait(),
                    timeout=5
                )
            except asyncio.TimeoutError:
                raise RuntimeError(
                    f"Timeout waiting for ACK #{sequence}"
                )

            if last_ack != sequence:
                raise RuntimeError(
                    f"Expected ACK #{sequence}, "
                    f"received ACK #{last_ack}"
                )

            print(f"[OK] ACK #{sequence} received")
            sequence += 1

        # ----------------------------------------------------
        # End route
        # ----------------------------------------------------

        print()
        print("[6] Sending END_ROUTE...")

        end_packet = build_end_packet(
            route
        )

        print(
            "END HEX:",
            end_packet.hex(" ").upper()
        )

        await client.write_gatt_char(
            CONTROL_UUID,
            end_packet,
            response=True
        )

        print("[OK] END_ROUTE sent")

        # ----------------------------------------------------
        # Give ESP32 time to verify
        # ----------------------------------------------------

        await asyncio.sleep(1)

        # ----------------------------------------------------
        # Read final status
        # ----------------------------------------------------

        print()
        print("[7] Reading final STATUS...")

        status = await client.read_gatt_char(
            STATUS_UUID
        )

        print(
            "[STATUS]",
            status.decode(
                "utf-8",
                errors="replace"
            )
        )

        # ----------------------------------------------------
        # Stop notifications
        # ----------------------------------------------------

        await client.stop_notify(
            STATUS_UUID
        )

    print()
    print("=" * 60)
    print("Transfer complete")
    print("=" * 60)


if __name__ == "__main__":

    try:

        asyncio.run(main())

    except KeyboardInterrupt:

        print("\nStopped by user.")

    except Exception as e:

        print()
        print("[ERROR]")
        print(e)