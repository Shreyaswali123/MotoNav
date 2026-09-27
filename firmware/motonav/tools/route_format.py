import struct
import zlib


# ============================================================
# MotoNav Route Format v1
# ============================================================

ROUTE_VERSION = 1


# ============================================================
# Maneuver types
# ============================================================

MANEUVER_STRAIGHT = 0
MANEUVER_LEFT = 1
MANEUVER_RIGHT = 2
MANEUVER_U_TURN = 3
MANEUVER_ROUNDABOUT = 4
MANEUVER_DESTINATION = 5


# ============================================================
# Coordinate conversion
# ============================================================

def encode_coordinate(value):
    """
    Convert decimal GPS coordinate to signed integer.

    Example:
        15.3647000
        -> 153647000
    """

    return int(round(value * 10_000_000))


# ============================================================
# Route point
# ============================================================

def pack_point(latitude, longitude):

    lat = encode_coordinate(latitude)
    lon = encode_coordinate(longitude)

    return struct.pack(
        "<ii",
        lat,
        lon
    )


# ============================================================
# Maneuver
# ============================================================

def pack_maneuver(
    point_index,
    maneuver_type,
    distance
):

    return struct.pack(
        "<HBI",
        point_index,
        maneuver_type,
        distance
    )


# ============================================================
# Build route
# ============================================================

def build_route(
    route_id,
    points,
    maneuvers
):

    data = bytearray()

    # --------------------------------------------------------
    # Header
    # --------------------------------------------------------

    data += struct.pack(
        "<BIHH",
        ROUTE_VERSION,
        route_id,
        len(points),
        len(maneuvers)
    )

    # --------------------------------------------------------
    # GPS points
    # --------------------------------------------------------

    for latitude, longitude in points:

        data += pack_point(
            latitude,
            longitude
        )

    # --------------------------------------------------------
    # Maneuvers
    # --------------------------------------------------------

    for point_index, maneuver_type, distance in maneuvers:

        data += pack_maneuver(
            point_index,
            maneuver_type,
            distance
        )

    return bytes(data)


# ============================================================
# Debug output
# ============================================================

def print_route(route):

    print("=" * 60)
    print("MotoNav Route Format v1")
    print("=" * 60)

    print(f"Route size: {len(route)} bytes")

    print()
    print("HEX:")

    for i in range(0, len(route), 16):

        chunk = route[i:i + 16]

        print(
            f"{i:04X}: "
            + " ".join(
                f"{b:02X}"
                for b in chunk
            )
        )

    print()

    crc = zlib.crc32(route) & 0xFFFFFFFF

    print(
        f"Route CRC32: 0x{crc:08X}"
    )

    print("=" * 60)


# ============================================================
# Test route
# ============================================================

if __name__ == "__main__":

    points = [

        (15.3647000, 75.1240000),

        (15.3658000, 75.1262000),

        (15.3681000, 75.1294000),

        (15.3700000, 75.1320000),

    ]

    maneuvers = [

        (
            1,
            MANEUVER_RIGHT,
            250
        ),

        (
            2,
            MANEUVER_LEFT,
            180
        ),

        (
            3,
            MANEUVER_DESTINATION,
            0
        ),

    ]

    route = build_route(
        route_id=1,
        points=points,
        maneuvers=maneuvers
    )

    print_route(route)