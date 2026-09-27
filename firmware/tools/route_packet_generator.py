import struct
import zlib


# ============================================================
# MotoNav BLE Route Packet Generator
# ============================================================

PACKET_ROUTE_DATA = 0x03

HEADER_SIZE = 6
CRC_SIZE = 4


def crc32(data: bytes) -> int:
    """Calculate MotoNav CRC32."""
    return zlib.crc32(data) & 0xFFFFFFFF


def build_route_packet(sequence: int, payload: bytes) -> bytes:
    """
    Build a ROUTE_DATA packet.

    Format:

    Byte 0       TYPE
    Byte 1-2     SEQUENCE
    Byte 3-4     PAYLOAD LENGTH
    Byte 5       FLAGS
    Byte 6...    PAYLOAD
    Last 4       CRC32
    """

    packet_type = PACKET_ROUTE_DATA
    flags = 0

    header = struct.pack(
        "<BHHB",
        packet_type,
        sequence,
        len(payload),
        flags
    )

    data_without_crc = header + payload

    packet_crc = crc32(data_without_crc)

    packet = (
        data_without_crc +
        struct.pack("<I", packet_crc)
    )

    return packet


def print_hex(data: bytes):
    """Print bytes in nRF Connect HEX format."""
    print(" ".join(f"{byte:02X}" for byte in data))


def generate_route_packets(route_text: str, chunk_size: int = 7):
    """
    Split route data into chunks and generate packets.
    """

    route = route_text.encode("utf-8")

    print()
    print("=" * 60)
    print("MotoNav Route Packet Generator")
    print("=" * 60)

    print(f"Route: {route_text}")
    print(f"Route size: {len(route)} bytes")
    print(f"Chunk size: {chunk_size} bytes")

    print()
    print("START_ROUTE")
    start_packet = (
    bytes([0x01]) +
    struct.pack("<I", len(route))
    )

    print("HEX:")
    print_hex(start_packet)
    print()

    packets = []

    sequence = 0

    for start in range(0, len(route), chunk_size):

        chunk = route[start:start + chunk_size]

        packet = build_route_packet(
            sequence,
            chunk
        )

        packets.append(packet)

        print(f"ROUTE_DATA #{sequence}")
        print(f"Payload: {chunk.decode('utf-8', errors='replace')}")
        print(f"Payload bytes: {len(chunk)}")

        print("HEX:")
        print_hex(packet)

        packet_crc = struct.unpack(
            "<I",
            packet[-4:]
        )[0]

        print(f"CRC32: 0x{packet_crc:08X}")
        print()

        sequence += 1

    final_crc = crc32(route)

    print("END_ROUTE")
    print(
        "HEX:",
        "02",
        " ".join(
            f"{byte:02X}"
            for byte in struct.pack("<I", final_crc)
        )
    )

    print(f"Final route CRC32: 0x{final_crc:08X}")

    print()
    print("=" * 60)

    return packets


if __name__ == "__main__":

    route = (
        "POINT_A"
        "POINT_B"
        "POINT_C"
    )

    generate_route_packets(
        route,
        chunk_size=7
    )