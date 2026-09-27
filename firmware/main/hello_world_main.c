#include <stdio.h>
#include <string.h>
#include <stdint.h>
#include <stdlib.h>
#include <inttypes.h>

#include "nvs_flash.h"
#include "nvs.h"
#include "esp_log.h"
#include "esp_err.h"
#include "esp_heap_caps.h"
#include "oled_display.h"
#include "driver/uart.h"
#include "driver/gpio.h"

#include "nimble/nimble_port.h"
#include "nimble/nimble_port_freertos.h"

#include "host/ble_hs.h"
#include "host/ble_gap.h"
#include "host/ble_gatt.h"
#include "host/ble_uuid.h"
#include "host/ble_sm.h"
#include "host/ble_store.h"
#include "host/util/util.h"

#include "services/gap/ble_svc_gap.h"
#include "services/gatt/ble_svc_gatt.h"

#include "esp_random.h"

#include <stdbool.h>
#include <math.h>

static const char *TAG = "MOTONAV";

typedef enum {
    PAIRING_UI_NONE = 0,
    PAIRING_UI_CODE,
    PAIRING_UI_AUTHENTICATED,
    PAIRING_UI_FAILED
} pairing_ui_state_t;

static volatile pairing_ui_state_t pairing_ui_state =
    PAIRING_UI_NONE;

static volatile uint32_t pairing_passkey = 0;
static volatile TickType_t pairing_ui_until = 0;
static portMUX_TYPE pairing_ui_mux =
    portMUX_INITIALIZER_UNLOCKED;

static void pairing_ui_set(
    pairing_ui_state_t state,
    uint32_t passkey,
    TickType_t duration)
{
    taskENTER_CRITICAL(&pairing_ui_mux);
    pairing_ui_state = state;
    pairing_passkey = passkey;
    pairing_ui_until =
        xTaskGetTickCount() + duration;
    taskEXIT_CRITICAL(&pairing_ui_mux);
}

static pairing_ui_state_t pairing_ui_get(
    uint32_t *passkey)
{
    pairing_ui_state_t state;

    taskENTER_CRITICAL(&pairing_ui_mux);
    state = pairing_ui_state;
    *passkey = pairing_passkey;

    if (state != PAIRING_UI_NONE &&
        xTaskGetTickCount() >= pairing_ui_until) {
        pairing_ui_state = PAIRING_UI_NONE;
        state = PAIRING_UI_NONE;
    }

    taskEXIT_CRITICAL(&pairing_ui_mux);
    return state;
}

/* =========================================================
 * GPS UART
 * ========================================================= */

#define GPS_UART_PORT       UART_NUM_1
#define GPS_UART_TX_PIN     GPIO_NUM_7
#define GPS_UART_RX_PIN     GPIO_NUM_8
#define GPS_UART_BAUD_RATE  9600
#define GPS_RX_BUFFER_SIZE  1024

/* =========================================================
 * MotoNav UUIDs
 * ========================================================= */

#define MOTONAV_SERVICE_UUID \
    BLE_UUID128_DECLARE(0x78, 0x56, 0x34, 0x12, \
                        0x34, 0x12, 0x78, 0x56, \
                        0x12, 0x34, 0x56, 0x78, \
                        0x9a, 0xbc, 0xde, 0xf0)

#define CONTROL_UUID \
    BLE_UUID128_DECLARE(0x78, 0x56, 0x34, 0x12, \
                        0x34, 0x12, 0x78, 0x56, \
                        0x12, 0x34, 0x56, 0x78, \
                        0x9a, 0xbc, 0xde, 0xf1)

#define STATUS_UUID \
    BLE_UUID128_DECLARE(0x78, 0x56, 0x34, 0x12, \
                        0x34, 0x12, 0x78, 0x56, \
                        0x12, 0x34, 0x56, 0x78, \
                        0x9a, 0xbc, 0xde, 0xf2)

#define ROUTE_DATA_UUID \
    BLE_UUID128_DECLARE(0x78, 0x56, 0x34, 0x12, \
                        0x34, 0x12, 0x78, 0x56, \
                        0x12, 0x34, 0x56, 0x78, \
                        0x9a, 0xbc, 0xde, 0xf3)


/* =========================================================
 * Protocol definitions
 * ========================================================= */

#define PACKET_START_ROUTE     0x01
#define PACKET_ROUTE_INFO      0x02
#define PACKET_ROUTE_DATA      0x03
#define PACKET_END_ROUTE       0x04
#define PACKET_ACK             0x05
#define PACKET_ERROR           0x06
#define PACKET_ROUTE_READY     0x07
#define PACKET_CANCEL          0x08


/* CONTROL commands */

#define CMD_START_ROUTE        0x01
#define CMD_END_ROUTE          0x02
#define CMD_CANCEL_ROUTE       0x03
#define CMD_GET_STATUS         0x04


/* =========================================================
 * Route buffer
 * ========================================================= */

#define ROUTE_BUFFER_SIZE      8192
#define MAX_PACKET_SIZE        512

#define ROUTE_NVS_NAMESPACE    "motonav"
#define ROUTE_NVS_BLOB_KEY     "route_blob"
#define ROUTE_NVS_SIZE_KEY     "route_size"
#define ROUTE_NVS_CRC_KEY      "route_crc"
#define ROUTE_NVS_VERSION_KEY  "route_ver"
#define ROUTE_NVS_VERSION      1


/* =========================================================
 * Route Format v1
 *
 * Total route size:
 *   9 + (point_count * 8) + (maneuver_count * 7)
 *
 * Header (9 bytes):
 *   Byte 0     VERSION (uint8)
 *   Byte 1-4   ROUTE ID (uint32)
 *   Byte 5-6   POINT COUNT (uint16)
 *   Byte 7-8   MANEUVER COUNT (uint16)
 *
 * Point (8 bytes):
 *   Byte 0-3   LATITUDE (int32, value / 1e7)
 *   Byte 4-7   LONGITUDE (int32, value / 1e7)
 *
 * Maneuver (7 bytes):
 *   Byte 0-1   POINT INDEX (uint16)
 *   Byte 2     TYPE (uint8)
 *   Byte 3-6   DISTANCE (uint32)
 *
 * All multi-byte values are little-endian.
 * ========================================================= */

#define ROUTE_FORMAT_VERSION       1

#define ROUTE_HEADER_SIZE           9
#define ROUTE_POINT_SIZE            8
#define ROUTE_MANEUVER_SIZE         7


#define MAX_ROUTE_POINTS            750
#define MAX_ROUTE_MANEUVERS         100


/* =========================================================
 * Maneuver types
 *
 * These values correspond to route_format.py
 * ========================================================= */

#define MANEUVER_RIGHT              2
#define MANEUVER_LEFT               1
#define MANEUVER_DESTINATION       5
#define PACKET_HEADER_SIZE     6
#define PACKET_CRC_SIZE        4

/* =========================================================
 * Route structures
 * ========================================================= */

/* =========================================================
 * Navigation definitions
 * ========================================================= */

#define WAYPOINT_REACHED_DISTANCE_M  20.0f
#define EARTH_RADIUS_M               6371000.0f
#define GPS_FIX_TIMEOUT_MS           5000
#define OFF_ROUTE_ENTER_DISTANCE_M   50.0
#define OFF_ROUTE_EXIT_DISTANCE_M    30.0
#define OFF_ROUTE_ENTER_FIXES        3
#define OFF_ROUTE_EXIT_FIXES         2
#define ROUTE_SEARCH_WINDOW         3
#define OFF_ROUTE_HDOP_MAX           2.5

typedef struct {
    double latitude;
    double longitude;
} gps_position_t;

static gps_position_t current_position;

static TickType_t last_gps_fix_time = 0;

static bool gps_fix_received = false;

static double current_hdop = NAN;

static portMUX_TYPE pos_mux = portMUX_INITIALIZER_UNLOCKED;

static portMUX_TYPE navigation_mux =
    portMUX_INITIALIZER_UNLOCKED;

static bool gps_get_fix_snapshot(
    gps_position_t *position,
    TickType_t *fix_time,
    double *hdop)
{
    bool fix_received;

    taskENTER_CRITICAL(&pos_mux);

    *position = current_position;
    *fix_time = last_gps_fix_time;
    *hdop = current_hdop;
    fix_received = gps_fix_received;

    taskEXIT_CRITICAL(&pos_mux);

    return fix_received;
}

static uint16_t current_waypoint = 0;

static uint8_t waypoint_hit_count = 0;

static bool navigation_active = false;

static TickType_t last_navigation_fix_time = 0;

static bool navigation_fix_processed = false;

typedef struct {
    bool valid;
    double distance_m;
    uint16_t nearest_segment_index;
    double recovery_latitude;
    double recovery_longitude;
    uint16_t point_count;
} route_geometry_result_t;

static bool off_route_active = false;
static uint8_t off_route_enter_count = 0;
static uint8_t off_route_exit_count = 0;
static route_geometry_result_t off_route_recovery;

/* Reset with the existing navigation lifecycle, not with the display task. */
static bool navigation_arrived = false;
static uint32_t navigation_generation = 0;

typedef struct {
    int32_t latitude;
    int32_t longitude;
} route_point_t;


typedef struct {
    uint16_t point_index;
    uint8_t type;
    uint32_t distance;
} maneuver_t;


/* =========================================================
 * BLE state
 * ========================================================= */

static uint8_t own_addr_type;

static uint16_t connection_handle =
    BLE_HS_CONN_HANDLE_NONE;

static uint16_t status_val_handle;


/* =========================================================
 * Route state
 * ========================================================= */

typedef enum {

    ROUTE_STATE_IDLE = 0,
    ROUTE_STATE_RECEIVING,
    ROUTE_STATE_VERIFYING,
    ROUTE_STATE_READY,
    ROUTE_STATE_ERROR

} route_state_t;


static route_state_t route_state =
    ROUTE_STATE_IDLE;

static portMUX_TYPE route_state_mux =
    portMUX_INITIALIZER_UNLOCKED;


static uint8_t route_buffer[ROUTE_BUFFER_SIZE];

static uint32_t route_received_bytes = 0;

static uint32_t route_expected_bytes = 0;

static uint16_t route_expected_sequence = 0;

static uint32_t route_crc = 0xFFFFFFFF;


/* =========================================================
 * Parsed route
 * ========================================================= */

static uint8_t route_version = 0;

static uint32_t route_id = 0;

static uint16_t route_point_count = 0;

static uint16_t route_maneuver_count = 0;

static route_point_t route_points[MAX_ROUTE_POINTS];

static maneuver_t route_maneuvers[MAX_ROUTE_MANEUVERS];

static esp_err_t route_persistence_save(
    uint32_t verified_crc);

static bool route_persistence_restore(void);

static esp_err_t route_persistence_erase(void);


static void off_route_reset_locked(void)
{
    off_route_active = false;
    off_route_enter_count = 0;
    off_route_exit_count = 0;
    off_route_recovery.valid = false;
    off_route_recovery.distance_m = 0.0;
    off_route_recovery.nearest_segment_index = UINT16_MAX;
    off_route_recovery.recovery_latitude = 0.0;
    off_route_recovery.recovery_longitude = 0.0;
    off_route_recovery.point_count = 0;
}


static void navigation_reset(void)
{
    taskENTER_CRITICAL(&navigation_mux);

    navigation_active = false;
    current_waypoint = 0;
    waypoint_hit_count = 0;
    last_navigation_fix_time = 0;
    navigation_fix_processed = false;
    off_route_reset_locked();
    navigation_arrived = false;
    navigation_generation++;

    taskEXIT_CRITICAL(&navigation_mux);
}


static bool navigation_start(void)
{
    bool started = false;

    taskENTER_CRITICAL(&route_state_mux);

    if (route_state == ROUTE_STATE_READY) {
        taskENTER_CRITICAL(&navigation_mux);

        navigation_active = true;
        current_waypoint = 0;
        waypoint_hit_count = 0;
        last_navigation_fix_time = 0;
        navigation_fix_processed = false;
        off_route_reset_locked();
        navigation_arrived = false;
        navigation_generation++;

        taskEXIT_CRITICAL(&navigation_mux);

        started = true;
    }

    taskEXIT_CRITICAL(&route_state_mux);

    return started;
}


static bool navigation_is_active(void)
{
    bool active;

    taskENTER_CRITICAL(&navigation_mux);

    active = navigation_active;

    taskEXIT_CRITICAL(&navigation_mux);

    return active;
}


static route_state_t route_state_get(void)
{
    route_state_t state;

    taskENTER_CRITICAL(&route_state_mux);

    state = route_state;

    taskEXIT_CRITICAL(&route_state_mux);

    return state;
}


static void route_state_set(route_state_t state)
{
    taskENTER_CRITICAL(&route_state_mux);

    route_state = state;

    if (state != ROUTE_STATE_READY) {
        navigation_reset();
    }

    taskEXIT_CRITICAL(&route_state_mux);
}


static void route_complete_if_ready(uint32_t generation, bool arrived)
{
    taskENTER_CRITICAL(&route_state_mux);
    taskENTER_CRITICAL(&navigation_mux);

    if (route_state == ROUTE_STATE_READY &&
        navigation_generation == generation) {
        route_state = ROUTE_STATE_IDLE;
        /* Keep the existing completion reset; retain only the arrival UI. */
        taskEXIT_CRITICAL(&navigation_mux);
        navigation_reset();
        taskENTER_CRITICAL(&navigation_mux);
        navigation_arrived = arrived;
    }

    taskEXIT_CRITICAL(&navigation_mux);
    taskEXIT_CRITICAL(&route_state_mux);
}


/* =========================================================
 * Utility functions
 * ========================================================= */

static uint16_t read_u16_le(
    const uint8_t *data)
{
    return
        ((uint16_t)data[0]) |
        ((uint16_t)data[1] << 8);
}


static uint32_t read_u32_le(
    const uint8_t *data)
{
    return
        ((uint32_t)data[0]) |
        ((uint32_t)data[1] << 8) |
        ((uint32_t)data[2] << 16) |
        ((uint32_t)data[3] << 24);
}


static int32_t read_i32_le(
    const uint8_t *data)
{
    return (int32_t)read_u32_le(data);
}


/* =========================================================
 * CRC32
 *
 * Standard CRC-32 / zlib compatible.
 * ========================================================= */

static uint32_t crc32_update(
    uint32_t crc,
    const uint8_t *data,
    size_t length)
{
    for (size_t i = 0; i < length; i++) {

        crc ^= data[i];

        for (int j = 0; j < 8; j++) {

            if (crc & 1) {

                crc =
                    (crc >> 1) ^
                    0xEDB88320;

            } else {

                crc >>= 1;
            }
        }
    }

    return crc;
}


/* =========================================================
 * Internal heap diagnostics
 * ========================================================= */

static void log_internal_heap(const char *location)
{
    size_t free_bytes =
        heap_caps_get_free_size(
            MALLOC_CAP_INTERNAL |
            MALLOC_CAP_8BIT
        );

    size_t largest_free_block =
        heap_caps_get_largest_free_block(
            MALLOC_CAP_INTERNAL |
            MALLOC_CAP_8BIT
        );

    ESP_LOGI(
        TAG,
        "Internal heap (%s): free=%lu bytes, largest=%lu bytes",
        location,
        (unsigned long)free_bytes,
        (unsigned long)largest_free_block
    );
}


/* =========================================================
 * Route parser
 * ========================================================= */

static int parse_route(void)
{
    ESP_LOGI(TAG, "================================");
    ESP_LOGI(TAG, "Parsing Route Format v1");
    ESP_LOGI(TAG, "================================");


    /* -----------------------------------------------------
     * Minimum header check
     * ----------------------------------------------------- */

    if (route_received_bytes < ROUTE_HEADER_SIZE) {

        ESP_LOGE(
            TAG,
            "Route too small for header: %lu bytes",
            (unsigned long)route_received_bytes
        );

        return -1;
    }


    const uint8_t *p = route_buffer;


    /* -----------------------------------------------------
     * Read header
     *
     * 01 01 00 00 00 04 00 03 00
     *
     * VERSION       = 01
     * ROUTE ID      = 00000001
     * POINT COUNT   = 0004
     * MANEUVER CNT  = 0003
     * ----------------------------------------------------- */

    route_version =
        p[0];

    route_id =
        read_u32_le(&p[1]);

    route_point_count =
        read_u16_le(&p[5]);

    route_maneuver_count =
        read_u16_le(&p[7]);


    ESP_LOGI(
        TAG,
        "Route version:      %u",
        route_version
    );

    ESP_LOGI(
        TAG,
        "Route ID:            %u",
        route_id
    );

    ESP_LOGI(
        TAG,
        "Point count:         %u",
        route_point_count
    );

    ESP_LOGI(
        TAG,
        "Maneuver count:      %u",
        route_maneuver_count
    );


    /* -----------------------------------------------------
     * Validate version
     * ----------------------------------------------------- */

    if (route_version != ROUTE_FORMAT_VERSION) {

        ESP_LOGE(
            TAG,
            "Unsupported route version: %u",
            route_version
        );

        return -1;
    }


    /* -----------------------------------------------------
     * Validate counts
     * ----------------------------------------------------- */

    if (route_point_count > MAX_ROUTE_POINTS) {

        ESP_LOGE(
            TAG,
            "Too many route points: %u",
            route_point_count
        );

        return -1;
    }


    if (route_point_count == 0) {

        ESP_LOGE(
            TAG,
            "Route must contain at least one point"
        );

        return -1;
    }


    if (route_maneuver_count > MAX_ROUTE_MANEUVERS) {

        ESP_LOGE(
            TAG,
            "Too many maneuvers: %u",
            route_maneuver_count
        );

        return -1;
    }


    /* -----------------------------------------------------
     * Calculate expected route size
     *
     * Header
     * + points
     * + maneuvers
     * ----------------------------------------------------- */

    uint32_t expected_size =
        ROUTE_HEADER_SIZE +
        ((uint32_t)route_point_count * ROUTE_POINT_SIZE) +
        ((uint32_t)route_maneuver_count * ROUTE_MANEUVER_SIZE);


    ESP_LOGI(
        TAG,
        "Expected format size: %lu bytes",
        (unsigned long)expected_size
    );

    ESP_LOGI(
        TAG,
        "Received route size:  %lu bytes",
        (unsigned long)route_received_bytes
    );


    if (expected_size != route_received_bytes) {

        ESP_LOGE(
            TAG,
            "Route format size mismatch"
        );

        return -1;
    }


    /* -----------------------------------------------------
     * Parse points
     * ----------------------------------------------------- */

    uint32_t offset =
        ROUTE_HEADER_SIZE;


    ESP_LOGI(
        TAG,
        "--------------------------------"
    );

    ESP_LOGI(
        TAG,
        "ROUTE POINTS"
    );

    ESP_LOGI(
        TAG,
        "--------------------------------"
    );


    for (
        uint16_t i = 0;
        i < route_point_count;
        i++
    ) {

        route_points[i].latitude =
            read_i32_le(
                &route_buffer[offset]
            );

        route_points[i].longitude =
            read_i32_le(
                &route_buffer[offset + 4]
            );

        if (
            route_points[i].latitude < -900000000 ||
            route_points[i].latitude > 900000000
        ) {
            ESP_LOGE(
                TAG,
                "Invalid latitude at point %u: %ld",
                i,
                (long)route_points[i].latitude
            );

            return -1;
        }

        if (
            route_points[i].longitude < -1800000000 ||
            route_points[i].longitude > 1800000000
        ) {
            ESP_LOGE(
                TAG,
                "Invalid longitude at point %u: %ld",
                i,
                (long)route_points[i].longitude
            );

            return -1;
        }


        ESP_LOGI(
            TAG,
            "Point #%u:",
            i
        );

        ESP_LOGI(
            TAG,
            "  Latitude:  %ld",
            (long)route_points[i].latitude
        );

        ESP_LOGI(
            TAG,
            "  Longitude: %ld",
            (long)route_points[i].longitude
        );


        offset += ROUTE_POINT_SIZE;
    }


    /* -----------------------------------------------------
     * Parse maneuvers
     * ----------------------------------------------------- */

    ESP_LOGI(
        TAG,
        "--------------------------------"
    );

    ESP_LOGI(
        TAG,
        "MANEUVERS"
    );

    ESP_LOGI(
        TAG,
        "--------------------------------"
    );


    for (
        uint16_t i = 0;
        i < route_maneuver_count;
        i++
    ) {

        uint16_t point_index =
            read_u16_le(
                &route_buffer[offset]
            );

        if (point_index >= route_point_count) {

            ESP_LOGE(
                TAG,
                "Maneuver %u has invalid point index: %u",
                i,
                point_index
            );

            return -1;
        }

        route_maneuvers[i].point_index =
            point_index;

        route_maneuvers[i].type =
            route_buffer[offset + 2];

        route_maneuvers[i].distance =
            read_u32_le(
                &route_buffer[offset + 3]
            );


        ESP_LOGI(
            TAG,
            "Maneuver #%u:",
            i
        );

        ESP_LOGI(
            TAG,
            "  Point index: %u",
            route_maneuvers[i].point_index
        );

        ESP_LOGI(
            TAG,
            "  Type:        %u",
            route_maneuvers[i].type
        );

        ESP_LOGI(
            TAG,
            "  Distance:    %lu",
            (unsigned long)route_maneuvers[i].distance
        );


        offset += ROUTE_MANEUVER_SIZE;
    }


    return 0;
}


/* =========================================================
 * Convert degrees to radians
 * ========================================================= */

static double deg_to_rad(double degrees)
{
    return degrees * M_PI / 180.0;
}

static double rad_to_deg(double radians)
{
    return radians * 180.0 / M_PI;
}


/* =========================================================
 * Calculate distance between two GPS coordinates
 * Haversine formula
 * ========================================================= */

static double calculate_distance(
    double lat1,
    double lon1,
    double lat2,
    double lon2)
{
    double lat1_rad = deg_to_rad(lat1);
    double lat2_rad = deg_to_rad(lat2);

    double dlat =
        deg_to_rad(lat2 - lat1);

    double dlon =
        deg_to_rad(lon2 - lon1);

    double a =
        sin(dlat / 2.0) *
        sin(dlat / 2.0) +

        cos(lat1_rad) *
        cos(lat2_rad) *
        sin(dlon / 2.0) *
        sin(dlon / 2.0);

    double c =
        2.0 *
        atan2(
            sqrt(a),
            sqrt(1.0 - a)
        );

    return EARTH_RADIUS_M * c;
}

static void to_xy(
    double latitude,
    double longitude,
    double reference_latitude,
    double reference_longitude,
    double *x,
    double *y)
{
    double reference_latitude_rad =
        deg_to_rad(reference_latitude);

    *x =
        deg_to_rad(longitude - reference_longitude) *
        EARTH_RADIUS_M *
        cos(reference_latitude_rad);

    *y =
        deg_to_rad(latitude - reference_latitude) *
        EARTH_RADIUS_M;
}

static double point_segment_m(
    double px,
    double py,
    double ax,
    double ay,
    double bx,
    double by,
    double *qx,
    double *qy)
{
    double dx = bx - ax;
    double dy = by - ay;
    double length_squared = dx * dx + dy * dy;
    double projection = 0.0;

    if (length_squared > 0.0) {
        projection =
            ((px - ax) * dx + (py - ay) * dy) /
            length_squared;

        if (projection < 0.0) {
            projection = 0.0;
        } else if (projection > 1.0) {
            projection = 1.0;
        }
    }

    *qx = ax + projection * dx;
    *qy = ay + projection * dy;

    return hypot(px - *qx, py - *qy);
}

static route_geometry_result_t route_geometry_search(
    const gps_position_t *position,
    uint16_t waypoint,
    bool full_route)
{
    route_geometry_result_t result = {
        .valid = false,
        .distance_m = 0.0,
        .nearest_segment_index = UINT16_MAX,
        .recovery_latitude = 0.0,
        .recovery_longitude = 0.0,
        .point_count = 0
    };

    taskENTER_CRITICAL(&route_state_mux);
    taskENTER_CRITICAL(&navigation_mux);

    if (route_state != ROUTE_STATE_READY || route_point_count < 2) {
        taskEXIT_CRITICAL(&navigation_mux);
        taskEXIT_CRITICAL(&route_state_mux);
        return result;
    }

    uint16_t first_segment = 0;
    uint16_t last_segment = route_point_count - 2;

    if (!full_route) {
        first_segment = waypoint > ROUTE_SEARCH_WINDOW
            ? waypoint - ROUTE_SEARCH_WINDOW
            : 0;

        uint32_t window_last =
            (uint32_t)waypoint + ROUTE_SEARCH_WINDOW;

        if (window_last < last_segment) {
            last_segment = (uint16_t)window_last;
        }
    }

    double reference_latitude = position->latitude;
    double reference_longitude = position->longitude;
    double point_x;
    double point_y;

    to_xy(
        position->latitude,
        position->longitude,
        reference_latitude,
        reference_longitude,
        &point_x,
        &point_y
    );

    for (uint16_t i = first_segment; i <= last_segment; i++) {
        double a_latitude =
            (double)route_points[i].latitude / 10000000.0;
        double a_longitude =
            (double)route_points[i].longitude / 10000000.0;
        double b_latitude =
            (double)route_points[i + 1].latitude / 10000000.0;
        double b_longitude =
            (double)route_points[i + 1].longitude / 10000000.0;
        double a_x;
        double a_y;
        double b_x;
        double b_y;
        double recovery_x;
        double recovery_y;

        to_xy(
            a_latitude,
            a_longitude,
            reference_latitude,
            reference_longitude,
            &a_x,
            &a_y
        );
        to_xy(
            b_latitude,
            b_longitude,
            reference_latitude,
            reference_longitude,
            &b_x,
            &b_y
        );

        double distance = point_segment_m(
            point_x,
            point_y,
            a_x,
            a_y,
            b_x,
            b_y,
            &recovery_x,
            &recovery_y
        );

        if (!result.valid || distance < result.distance_m) {
            double reference_cosine =
                cos(deg_to_rad(reference_latitude));

            if (fabs(reference_cosine) < 1.0e-12) {
                reference_cosine =
                    reference_cosine < 0.0 ? -1.0e-12 : 1.0e-12;
            }

            result.valid = true;
            result.distance_m = distance;
            result.nearest_segment_index = i;
            result.recovery_latitude =
                reference_latitude +
                rad_to_deg(recovery_y / EARTH_RADIUS_M);
            result.recovery_longitude =
                reference_longitude +
                rad_to_deg(
                    recovery_x /
                    (EARTH_RADIUS_M * reference_cosine)
                );
        }
    }

    result.point_count = route_point_count;

    taskEXIT_CRITICAL(&navigation_mux);
    taskEXIT_CRITICAL(&route_state_mux);

    return result;
}

/* =========================================================
 * Get current route target
 * ========================================================= */

static bool route_target_get(
    uint16_t index,
    uint32_t generation,
    double *latitude,
    double *longitude,
    uint16_t *point_count,
    bool *maneuver_point,
    bool *destination_point)
{
    bool available = false;

    taskENTER_CRITICAL(&route_state_mux);
    taskENTER_CRITICAL(&navigation_mux);

    if (
        route_state == ROUTE_STATE_READY &&
        navigation_generation == generation &&
        index < route_point_count
    ) {
        *latitude =
            (double)route_points[index].latitude
            / 10000000.0;

        *longitude =
            (double)route_points[index].longitude
            / 10000000.0;

        *point_count = route_point_count;
        *maneuver_point = false;
        *destination_point = false;
        for (uint16_t i = 0; i < route_maneuver_count; i++) {
            if (route_maneuvers[i].point_index == index) {
                *maneuver_point = true;
                if (route_maneuvers[i].type == MANEUVER_DESTINATION) {
                    *destination_point = true;
                }
            }
        }

        available = true;
    }

    taskEXIT_CRITICAL(&navigation_mux);
    taskEXIT_CRITICAL(&route_state_mux);

    return available;
}

/* =========================================================
 * Navigation update
 * ========================================================= */

static void navigation_update(void)
{
    bool active;
    uint16_t waypoint;
    uint32_t generation;

    taskENTER_CRITICAL(&navigation_mux);

    active = navigation_active;
    waypoint = current_waypoint;
    generation = navigation_generation;

    taskEXIT_CRITICAL(&navigation_mux);

    if (!active || route_state_get() != ROUTE_STATE_READY) {
        return;
    }

    TickType_t last_fix;
    gps_position_t position;
    double hdop;

    bool fix_received =
        gps_get_fix_snapshot(
            &position,
            &last_fix,
            &hdop
        );

    TickType_t now = xTaskGetTickCount();
    uint32_t age_ms =
        (uint32_t)(
            (now - last_fix) *
            portTICK_PERIOD_MS
        );

    if (
        !fix_received ||
        age_ms > GPS_FIX_TIMEOUT_MS
    ) {

        taskENTER_CRITICAL(&navigation_mux);

        if (navigation_generation == generation && current_waypoint == waypoint) {
            waypoint_hit_count = 0;
            last_navigation_fix_time = 0;
            navigation_fix_processed = false;
            off_route_reset_locked();
        }

        taskEXIT_CRITICAL(&navigation_mux);

        /* The display task logs GPS waiting/lost only on state changes. */
        return;
    }

    double target_lat;
    double target_lon;
    uint16_t point_count;
    bool maneuver_point;
    bool destination_point;

    if (!route_target_get(
            waypoint,
            generation,
            &target_lat,
            &target_lon,
            &point_count,
            &maneuver_point,
            &destination_point
        )) {

        route_complete_if_ready(generation, false);

        return;
    }

    bool new_fix = false;

    taskENTER_CRITICAL(&navigation_mux);

    if (
        navigation_active &&
        navigation_generation == generation &&
        current_waypoint == waypoint &&
        (
            !navigation_fix_processed ||
            last_navigation_fix_time != last_fix
        )
    ) {
        last_navigation_fix_time = last_fix;
        navigation_fix_processed = true;
        new_fix = true;
    }

    taskEXIT_CRITICAL(&navigation_mux);

    if (!new_fix) {
        return;
    }

    bool off_route = false;
    taskENTER_CRITICAL(&navigation_mux);
    off_route = off_route_active;
    taskEXIT_CRITICAL(&navigation_mux);

    if (isfinite(hdop) && hdop <= OFF_ROUTE_HDOP_MAX) {
        route_geometry_result_t geometry = route_geometry_search(
            &position,
            waypoint,
            off_route
        );

        if (geometry.valid) {
            bool entered = false;
            bool recovered = false;
            bool enter_threshold_reached = false;
            bool navigation_current = false;
            route_geometry_result_t full_geometry = {0};

            taskENTER_CRITICAL(&navigation_mux);

            navigation_current =
                navigation_active &&
                navigation_generation == generation;

            if (navigation_current && off_route_active) {
                off_route_recovery = geometry;
                off_route_exit_count =
                    geometry.distance_m < OFF_ROUTE_EXIT_DISTANCE_M
                    ? (uint8_t)(off_route_exit_count + 1)
                    : 0;

                if (off_route_exit_count >= OFF_ROUTE_EXIT_FIXES) {
                    uint16_t resume_waypoint =
                        (uint16_t)(
                            geometry.nearest_segment_index + 1
                        );

                    if (resume_waypoint > current_waypoint) {
                        if (resume_waypoint >= route_point_count) {
                            resume_waypoint = route_point_count - 1;
                        }
                        current_waypoint = resume_waypoint;
                    }

                    off_route_reset_locked();
                    waypoint_hit_count = 0;
                    last_navigation_fix_time = 0;
                    navigation_fix_processed = false;
                    recovered = true;
                }
            } else if (navigation_current) {
                off_route_enter_count =
                    geometry.distance_m > OFF_ROUTE_ENTER_DISTANCE_M
                    ? (uint8_t)(off_route_enter_count + 1)
                    : 0;
                enter_threshold_reached =
                    off_route_enter_count >= OFF_ROUTE_ENTER_FIXES;
            }

            taskEXIT_CRITICAL(&navigation_mux);

            if (navigation_current &&
                !off_route &&
                enter_threshold_reached) {
                full_geometry = route_geometry_search(
                    &position,
                    waypoint,
                    true
                );

                taskENTER_CRITICAL(&navigation_mux);

                if (navigation_active &&
                    navigation_generation == generation &&
                    !off_route_active &&
                    full_geometry.valid) {
                    off_route_active = true;
                    off_route_recovery = full_geometry;
                    off_route_exit_count = 0;
                    entered = true;
                }

                taskEXIT_CRITICAL(&navigation_mux);
            }

            if (entered) {
                ESP_LOGW(
                    TAG,
                    "OFF ROUTE: %.1f m, segment %u",
                    full_geometry.distance_m,
                    full_geometry.nearest_segment_index
                );
            } else if (recovered) {
                ESP_LOGI(TAG, "OFF ROUTE CLEARED - resuming navigation");
            }
        }
    } else {
        taskENTER_CRITICAL(&navigation_mux);

        if (
            navigation_active &&
            navigation_generation == generation &&
            current_waypoint == waypoint
        ) {
            off_route_enter_count = 0;
            off_route_exit_count = 0;
        }

        taskEXIT_CRITICAL(&navigation_mux);
    }

    taskENTER_CRITICAL(&navigation_mux);
    off_route = off_route_active;
    taskEXIT_CRITICAL(&navigation_mux);

    if (off_route) {
        return;
    }

    double distance =
        calculate_distance(
            position.latitude,
            position.longitude,
            target_lat,
            target_lon
        );


    ESP_LOGD(
        TAG,
        "Navigation:"
    );

    ESP_LOGD(
        TAG,
        "  Current position: %.7f, %.7f",
        position.latitude,
        position.longitude
    );

    ESP_LOGD(
        TAG,
        "  Target waypoint:  %u",
        waypoint
    );

    ESP_LOGD(
        TAG,
        "  Target position:  %.7f, %.7f",
        target_lat,
        target_lon
    );

    ESP_LOGD(
        TAG,
        "  Distance: %.2f m",
        distance
    );


    if (distance <= WAYPOINT_REACHED_DISTANCE_M) {

        bool waypoint_reached = false;
        bool destination_reached = false;
        uint8_t hit_count = 0;

        if (route_state_get() != ROUTE_STATE_READY) {
            return;
        }

        taskENTER_CRITICAL(&navigation_mux);

        if (
            navigation_active &&
            navigation_generation == generation &&
            current_waypoint == waypoint
        ) {
            waypoint_hit_count++;
            hit_count = waypoint_hit_count;

            if (waypoint_hit_count >= 2) {
                waypoint_hit_count = 0;
                waypoint_reached = true;

                current_waypoint++;

                if (destination_point || current_waypoint >= point_count) {
                    navigation_active = false;
                    destination_reached = true;
                }
            }
        }

        taskEXIT_CRITICAL(&navigation_mux);

        if (hit_count > 0) {
            ESP_LOGI(
                TAG,
                "Waypoint %u within %.2f m (%u/2)",
                waypoint,
                distance,
                hit_count
            );
        }

        if (waypoint_reached) {

            if (maneuver_point) {
                ESP_LOGI(TAG, "Maneuver point %u reached", waypoint);
            }

            ESP_LOGI(
                TAG,
                "WAYPOINT %u REACHED",
                waypoint
            );

            if (destination_reached) {

                ESP_LOGI(
                    TAG,
                    "================================"
                );

                ESP_LOGI(
                    TAG,
                    "DESTINATION REACHED"
                );

                ESP_LOGI(
                    TAG,
                    "================================"
                );

                route_complete_if_ready(generation, true);

            } else {

                ESP_LOGI(
                    TAG,
                    "Next waypoint: %u",
                    waypoint + 1
                );
            }
        }

    } else {

        taskENTER_CRITICAL(&navigation_mux);

        if (navigation_generation == generation && current_waypoint == waypoint) {
            waypoint_hit_count = 0;
        }

        taskEXIT_CRITICAL(&navigation_mux);
    }
}

/* =========================================================
 * Guidance snapshots: no route pointers escape the route lock.
 * ========================================================= */

typedef struct {
    uint32_t generation;
    bool route_ready;
    bool arrived;
    bool off_route;
    route_geometry_result_t recovery;
    bool has_maneuver;
    maneuver_t maneuver;
    route_point_t point;
} guidance_snapshot_t;

static guidance_snapshot_t guidance_get_snapshot(void)
{
    guidance_snapshot_t snapshot = {0};

    /* Same lock order as navigation_start() and route_state_set(). */
    taskENTER_CRITICAL(&route_state_mux);
    taskENTER_CRITICAL(&navigation_mux);

    snapshot.generation = navigation_generation;
    snapshot.arrived = navigation_arrived;
    snapshot.route_ready = route_state == ROUTE_STATE_READY;
    snapshot.off_route = off_route_active;
    snapshot.recovery = off_route_recovery;
    if (snapshot.route_ready) {
        for (uint16_t i = 0; i < route_maneuver_count; i++) {
            const maneuver_t *maneuver = &route_maneuvers[i];
            if (maneuver->point_index >= current_waypoint &&
                maneuver->point_index < route_point_count &&
                (!snapshot.has_maneuver ||
                 maneuver->point_index < snapshot.maneuver.point_index)) {
                snapshot.has_maneuver = true;
                snapshot.maneuver = *maneuver;
            }
        }
        if (snapshot.has_maneuver) {
            snapshot.point = route_points[snapshot.maneuver.point_index];
        }
    }

    taskEXIT_CRITICAL(&navigation_mux);
    taskEXIT_CRITICAL(&route_state_mux);
    return snapshot;
}

typedef enum {
    GUIDANCE_NO_ROUTE = 0,
    GUIDANCE_GPS_WAITING,
    GUIDANCE_GPS_LOST,
    GUIDANCE_MANEUVER,
    GUIDANCE_ARRIVED,
    GUIDANCE_NO_TURNS,
    GUIDANCE_OFF_ROUTE,
    GUIDANCE_PAIRING
} guidance_state_t;

typedef struct {
    guidance_state_t state;
    oled_icon_t icon;
    const char *title;
    char detail[24];
    double distance;
} guidance_view_t;

static double initial_bearing_degrees(
    const gps_position_t *from,
    double to_latitude,
    double to_longitude)
{
    double from_latitude = deg_to_rad(from->latitude);
    double to_latitude_rad = deg_to_rad(to_latitude);
    double delta_longitude =
        deg_to_rad(to_longitude - from->longitude);
    double bearing = rad_to_deg(atan2(
        sin(delta_longitude) * cos(to_latitude_rad),
        cos(from_latitude) * sin(to_latitude_rad) -
        sin(from_latitude) * cos(to_latitude_rad) *
        cos(delta_longitude)
    ));

    if (bearing < 0.0) {
        bearing += 360.0;
    }

    return bearing;
}

static const char *bearing_direction(double bearing)
{
    static const char *directions[] = {
        "N", "NE", "E", "SE", "S", "SW", "W", "NW"
    };
    int direction_index =
        (int)floor((bearing + 22.5) / 45.0) % 8;

    return directions[direction_index];
}

static guidance_view_t guidance_make_view(
    const guidance_snapshot_t *snapshot,
    const gps_position_t *position,
    bool fix_received,
    uint32_t age_ms)
{
    guidance_view_t view = {
        .state = GUIDANCE_NO_ROUTE,
        .icon = OLED_ICON_NONE,
        .title = "MOTONAV",
        .detail = "NO ROUTE"
    };

    if (!snapshot->route_ready && !snapshot->arrived) {
        return view;
    }
    if (!fix_received || age_ms > GPS_FIX_TIMEOUT_MS) {
        view.state = fix_received ? GUIDANCE_GPS_LOST : GUIDANCE_GPS_WAITING;
        view.title = fix_received ? "GPS SIGNAL" : "GPS";
        snprintf(view.detail, sizeof(view.detail), "%s",
                 fix_received ? "LOST" : "WAITING");
        return view;
    }
    if (snapshot->arrived) {
        view.state = GUIDANCE_ARRIVED;
        view.icon = OLED_ICON_DESTINATION;
        view.title = "DESTINATION";
        snprintf(view.detail, sizeof(view.detail), "ARRIVED");
        return view;
    }
    if (snapshot->off_route && snapshot->recovery.valid) {
        view.state = GUIDANCE_OFF_ROUTE;
        view.title = "OFF ROUTE";
        view.distance = snapshot->recovery.distance_m;

        double bearing = initial_bearing_degrees(
            position,
            snapshot->recovery.recovery_latitude,
            snapshot->recovery.recovery_longitude
        );

        if (view.distance >= 1000.0) {
            snprintf(
                view.detail,
                sizeof(view.detail),
                "%.1f KM %s",
                view.distance / 1000.0,
                bearing_direction(bearing)
            );
        } else {
            snprintf(
                view.detail,
                sizeof(view.detail),
                "%u M %s",
                (unsigned int)fmin(
                    999.0,
                    floor(view.distance + 0.5)
                ),
                bearing_direction(bearing)
            );
        }

        return view;
    }
    if (!snapshot->has_maneuver) {
        view.state = GUIDANCE_NO_TURNS;
        snprintf(view.detail, sizeof(view.detail), "NO TURNS");
        return view;
    }

    view.state = GUIDANCE_MANEUVER;
    switch (snapshot->maneuver.type) {
        case MANEUVER_LEFT:
            view.icon = OLED_ICON_LEFT;
            view.title = "LEFT";
            break;
        case MANEUVER_RIGHT:
            view.icon = OLED_ICON_RIGHT;
            view.title = "RIGHT";
            break;
        case MANEUVER_DESTINATION:
            view.icon = OLED_ICON_DESTINATION;
            view.title = "DESTINATION";
            break;
        default:
            /* Unknown types must not be presented as a left/right turn. */
            view.title = "MANEUVER";
            break;
    }

    view.distance = calculate_distance(
        position->latitude, position->longitude,
        (double)snapshot->point.latitude / 10000000.0,
        (double)snapshot->point.longitude / 10000000.0);
    if (view.distance >= 1000.0) {
        snprintf(view.detail, sizeof(view.detail), "%.1f KM", view.distance / 1000.0);
    } else {
        /* Clamp rounding near the unit boundary to keep metres below 1000. */
        snprintf(view.detail, sizeof(view.detail), "%u M",
                 (unsigned int)fmin(999.0, floor(view.distance + 0.5)));
    }
    return view;
}

static void oled_navigation_task(void *arg)
{
    (void)arg;
    int last_state = -1;
    uint32_t last_generation = 0;
    uint16_t last_point = UINT16_MAX;
    double last_distance = -1.0;
    esp_err_t last_error = ESP_OK;

    while (1) {
        guidance_snapshot_t snapshot = guidance_get_snapshot();
        gps_position_t position;
        TickType_t fix_time;
        double hdop;
        bool fix_received = gps_get_fix_snapshot(
            &position,
            &fix_time,
            &hdop
        );
        uint32_t age_ms = (uint32_t)((xTaskGetTickCount() - fix_time) * portTICK_PERIOD_MS);
        guidance_view_t view = guidance_make_view(&snapshot, &position, fix_received, age_ms);
        uint32_t passkey = 0;
        pairing_ui_state_t pairing_state =
            pairing_ui_get(&passkey);

        if (pairing_state != PAIRING_UI_NONE) {
            view.state = GUIDANCE_PAIRING;
            view.icon = OLED_ICON_NONE;

            if (pairing_state == PAIRING_UI_CODE) {
                view.title = "PAIR DEVICE";
                snprintf(
                    view.detail,
                    sizeof(view.detail),
                    "CODE: %06" PRIu32,
                    passkey
                );
            } else if (pairing_state == PAIRING_UI_AUTHENTICATED) {
                view.title = "AUTHENTICATED";
                snprintf(view.detail, sizeof(view.detail), "READY");
            } else {
                view.title = "PAIRING FAILED";
                snprintf(view.detail, sizeof(view.detail), "TRY AGAIN");
            }
        }

        /* Drop a calculation if a route reset/start occurred in the meantime.
         * Never hold a critical section across OLED I2C operations. */
        taskENTER_CRITICAL(&navigation_mux);
        bool current = snapshot.generation == navigation_generation;
        taskEXIT_CRITICAL(&navigation_mux);
        if (!current) {
            vTaskDelay(pdMS_TO_TICKS(10));
            continue;
        }

        bool changed = last_state != (int)view.state ||
                       last_generation != snapshot.generation;
        if (view.state == GUIDANCE_MANEUVER &&
            (changed || last_point != snapshot.maneuver.point_index ||
             fabs(view.distance - last_distance) >= 50.0)) {
            ESP_LOGI(TAG, "Next maneuver: %s at point %u, distance %.0f m",
                     view.title, snapshot.maneuver.point_index, view.distance);
            last_point = snapshot.maneuver.point_index;
            last_distance = view.distance;
        } else if (changed) {
            if (view.state == GUIDANCE_GPS_LOST) {
                ESP_LOGW(TAG, "GPS stale - navigation display waiting");
            } else {
                ESP_LOGI(TAG, "Navigation display: %s %s", view.title, view.detail);
            }
        }
        last_state = view.state;
        last_generation = snapshot.generation;

        esp_err_t err;

        if (pairing_state == PAIRING_UI_CODE) {
            char code[16];
            snprintf(
                code,
                sizeof(code),
                "CODE: %06" PRIu32,
                passkey
            );
            err = oled_display_pairing(
                "PAIR DEVICE",
                code,
                "ENTER ON PHONE"
            );
        } else {
            err = oled_display_guidance(
                view.icon,
                view.title,
                view.detail
            );
        }
        if (err != last_error) {
            ESP_LOGW(TAG, "Navigation display I2C: %s", esp_err_to_name(err));
            last_error = err;
        }
        /* Replacement can also happen during a page transfer. Refresh that
         * frame immediately instead of retaining the old route for a second. */
        taskENTER_CRITICAL(&navigation_mux);
        current = snapshot.generation == navigation_generation;
        taskEXIT_CRITICAL(&navigation_mux);
        if (!current) {
            vTaskDelay(pdMS_TO_TICKS(10));
            continue;
        }
        vTaskDelay(pdMS_TO_TICKS(1000));
    }
}

static int nmea_hex_value(char value)
{
    if (value >= '0' && value <= '9') {
        return value - '0';
    }

    if (value >= 'A' && value <= 'F') {
        return value - 'A' + 10;
    }

    if (value >= 'a' && value <= 'f') {
        return value - 'a' + 10;
    }

    return -1;
}


static bool nmea_checksum_valid(const char *line)
{
    if (line[0] != '$') {
        return false;
    }

    const char *checksum_marker = strrchr(line, '*');

    if (
        checksum_marker == NULL ||
        checksum_marker[1] == '\0' ||
        checksum_marker[2] == '\0' ||
        checksum_marker[3] != '\0'
    ) {
        return false;
    }

    int high = nmea_hex_value(checksum_marker[1]);
    int low = nmea_hex_value(checksum_marker[2]);

    if (high < 0 || low < 0) {
        return false;
    }

    uint8_t checksum = 0;

    for (
        const char *p = line + 1;
        p < checksum_marker;
        p++
    ) {
        checksum ^= (uint8_t)*p;
    }

    return checksum == (uint8_t)((high << 4) | low);
}


static void gps_parse_nmea(
    const char *line
)
{
    char type[16];

    double time;
    double latitude_raw;
    double longitude_raw;
    double hdop = NAN;

    char latitude_dir;
    char longitude_dir;

    int fix_quality;

    if (!nmea_checksum_valid(line)) {
        return;
    }

    int parsed =
        sscanf(
            line,
            "$%15[^,],%lf,%lf,%c,%lf,%c,%d,%*d,%lf",
            type,
            &time,
            &latitude_raw,
            &latitude_dir,
            &longitude_raw,
            &longitude_dir,
            &fix_quality,
            &hdop
        );

    if (
        parsed < 7 ||
        ((strcmp(type, "GNGGA") != 0) &&
         (strcmp(type, "GPGGA") != 0))
    ) {
        return;
    }

    if (fix_quality < 1 || fix_quality > 5) {
        return;
    }

    if (
        !isfinite(time) ||
        !isfinite(latitude_raw) ||
        !isfinite(longitude_raw) ||
        time < 0.0 ||
        time >= 240000.0 ||
        latitude_raw < 0.0 ||
        latitude_raw > 9000.0 ||
        longitude_raw < 0.0 ||
        longitude_raw > 18000.0 ||
        (latitude_dir != 'N' && latitude_dir != 'S') ||
        (longitude_dir != 'E' && longitude_dir != 'W')
    ) {
        return;
    }


    /*
     * Convert NMEA latitude:
     *
     * 1522.0729
     *
     * = 15 degrees
     * + 22.0729 minutes
     */

    int latitude_degrees =
        (int)(latitude_raw / 100.0);

    double latitude_minutes =
        latitude_raw -
        (latitude_degrees * 100.0);

    if (
        latitude_degrees > 90 ||
        latitude_minutes < 0.0 ||
        latitude_minutes >= 60.0 ||
        (latitude_degrees == 90 && latitude_minutes != 0.0)
    ) {
        return;
    }

    double latitude =
        latitude_degrees +
        (latitude_minutes / 60.0);


    /*
     * Convert NMEA longitude:
     *
     * 07507.4150
     *
     * = 75 degrees
     * + 7.4150 minutes
     */

    int longitude_degrees =
        (int)(longitude_raw / 100.0);

    double longitude_minutes =
        longitude_raw -
        (longitude_degrees * 100.0);

    if (
        longitude_degrees > 180 ||
        longitude_minutes < 0.0 ||
        longitude_minutes >= 60.0 ||
        (longitude_degrees == 180 && longitude_minutes != 0.0)
    ) {
        return;
    }

    double longitude =
        longitude_degrees +
        (longitude_minutes / 60.0);


    if (latitude_dir == 'S') {
        latitude = -latitude;
    }

    if (longitude_dir == 'W') {
        longitude = -longitude;
    }
    taskENTER_CRITICAL(&pos_mux);

    current_position.latitude =
        latitude;

    current_position.longitude =
        longitude;

    current_hdop = hdop;

    last_gps_fix_time = xTaskGetTickCount();    

    gps_fix_received = true;

    taskEXIT_CRITICAL(&pos_mux);

    ESP_LOGI(
        TAG,
        "GPS FIX: %.7f, %.7f",
        latitude,
        longitude
    );
}

static void navigation_simulation_task(
    void *arg)
{
    (void)arg;


    ESP_LOGI(
        TAG,
        "Navigation simulation task started"
    );


    while (1) {

        if (navigation_start()) {


                        ESP_LOGI(
                TAG,
                "================================"
            );

            ESP_LOGI(
                TAG,
                "STARTING GPS NAVIGATION"
            );

            ESP_LOGI(
                TAG,
                "================================"
            );


            /*
             * Use live GPS position.
             *
             * GPS parser updates current_position.
             * navigation_update() uses that position
             * to determine waypoint progress.
             */

            while (
                route_state_get() == ROUTE_STATE_READY &&
                navigation_is_active()
            ) {

                navigation_update();

                vTaskDelay(
                    pdMS_TO_TICKS(1000)
                );
            }

        }

        vTaskDelay(
            pdMS_TO_TICKS(500)
        );
    }
}


/* =========================================================
 * Print parsed route
 * ========================================================= */

static void print_route(void)
{
    ESP_LOGI(TAG, "================================");
    ESP_LOGI(TAG, "       DECODED ROUTE");
    ESP_LOGI(TAG, "================================");


    ESP_LOGI(
        TAG,
        "Route ID: %u",
        route_id
    );

    ESP_LOGI(
        TAG,
        "Points:   %u",
        route_point_count
    );

    ESP_LOGI(
        TAG,
        "Maneuvers:%u",
        route_maneuver_count
    );


    /* -----------------------------------------------------
     * Points
     * ----------------------------------------------------- */

    ESP_LOGI(
        TAG,
        "--------------------------------"
    );

    for (
        uint16_t i = 0;
        i < route_point_count;
        i++
    ) {

        ESP_LOGI(
            TAG,
            "POINT %u: lat=%ld lon=%ld",
            i,
            (long)route_points[i].latitude,
            (long)route_points[i].longitude
        );
    }


    /* -----------------------------------------------------
     * Maneuvers
     * ----------------------------------------------------- */

    ESP_LOGI(
        TAG,
        "--------------------------------"
    );

    for (
        uint16_t i = 0;
        i < route_maneuver_count;
        i++
    ) {

        const char *type_name =
            "UNKNOWN";


        switch (
            route_maneuvers[i].type
        ) {

            case MANEUVER_LEFT:
                type_name = "LEFT";
                break;

            case MANEUVER_RIGHT:
                type_name = "RIGHT";
                break;

            case MANEUVER_DESTINATION:
                type_name = "DESTINATION";
                break;

            default:
                break;
        }


        ESP_LOGI(
            TAG,
            "MANEUVER %u: point=%u type=%u (%s) distance=%lu",
            i,
            route_maneuvers[i].point_index,
            route_maneuvers[i].type,
            type_name,
            (unsigned long)route_maneuvers[i].distance
        );
    }


    ESP_LOGI(TAG, "================================");
}


/* =========================================================
 * Route reset
 * ========================================================= */

static void route_reset(void)
{
    route_state_set(
        ROUTE_STATE_IDLE
    );

    route_received_bytes = 0;

    route_expected_bytes = 0;

    route_expected_sequence = 0;

    route_crc = 0xFFFFFFFF;


    route_version = 0;
    route_id = 0;
    route_point_count = 0;
    route_maneuver_count = 0;


    memset(
        route_points,
        0,
        sizeof(route_points)
    );

    memset(
        route_maneuvers,
        0,
        sizeof(route_maneuvers)
    );


    ESP_LOGI(
        TAG,
        "Route buffer reset"
    );
}


static esp_err_t route_persistence_erase(void)
{
    nvs_handle_t handle;
    esp_err_t err = nvs_open(
        ROUTE_NVS_NAMESPACE,
        NVS_READWRITE,
        &handle
    );

    if (err == ESP_ERR_NVS_NOT_FOUND) {
        return ESP_OK;
    }

    if (err != ESP_OK) {
        ESP_LOGE(
            TAG,
            "Route NVS erase open failed: %s",
            esp_err_to_name(err)
        );
        return err;
    }

    const char *keys[] = {
        ROUTE_NVS_BLOB_KEY,
        ROUTE_NVS_SIZE_KEY,
        ROUTE_NVS_CRC_KEY,
        ROUTE_NVS_VERSION_KEY
    };

    for (size_t i = 0; i < sizeof(keys) / sizeof(keys[0]); i++) {
        err = nvs_erase_key(handle, keys[i]);
        if (err != ESP_OK && err != ESP_ERR_NVS_NOT_FOUND) {
            ESP_LOGE(
                TAG,
                "Route NVS erase key %s failed: %s",
                keys[i],
                esp_err_to_name(err)
            );
            nvs_close(handle);
            return err;
        }
    }

    err = nvs_commit(handle);
    if (err != ESP_OK) {
        ESP_LOGE(
            TAG,
            "Route NVS erase commit failed: %s",
            esp_err_to_name(err)
        );
    }

    nvs_close(handle);
    return err;
}


static esp_err_t route_persistence_save(
    uint32_t verified_crc)
{
    nvs_handle_t handle;
    esp_err_t err = nvs_open(
        ROUTE_NVS_NAMESPACE,
        NVS_READWRITE,
        &handle
    );

    if (err != ESP_OK) {
        ESP_LOGE(
            TAG,
            "Route NVS save open failed: %s",
            esp_err_to_name(err)
        );
        return err;
    }

    err = nvs_set_blob(
        handle,
        ROUTE_NVS_BLOB_KEY,
        route_buffer,
        route_received_bytes
    );

    if (err == ESP_OK) {
        err = nvs_set_u32(
            handle,
            ROUTE_NVS_SIZE_KEY,
            route_received_bytes
        );
    }

    if (err == ESP_OK) {
        err = nvs_set_u32(
            handle,
            ROUTE_NVS_CRC_KEY,
            verified_crc
        );
    }

    if (err == ESP_OK) {
        err = nvs_set_u32(
            handle,
            ROUTE_NVS_VERSION_KEY,
            ROUTE_NVS_VERSION
        );
    }

    if (err == ESP_OK) {
        err = nvs_commit(handle);
    }

    if (err != ESP_OK) {
        ESP_LOGE(
            TAG,
            "Route NVS save failed: %s",
            esp_err_to_name(err)
        );
    } else {
        ESP_LOGI(
            TAG,
            "Verified route persisted: %lu bytes, CRC=0x%08lX",
            (unsigned long)route_received_bytes,
            (unsigned long)verified_crc
        );
    }

    nvs_close(handle);
    return err;
}


static bool route_persistence_restore(void)
{
    nvs_handle_t handle;
    esp_err_t err = nvs_open(
        ROUTE_NVS_NAMESPACE,
        NVS_READONLY,
        &handle
    );

    if (err == ESP_ERR_NVS_NOT_FOUND) {
        ESP_LOGI(TAG, "No persisted route found");
        return false;
    }

    if (err != ESP_OK) {
        ESP_LOGE(
            TAG,
            "Route NVS restore open failed: %s",
            esp_err_to_name(err)
        );
        return false;
    }

    uint32_t stored_size = 0;
    uint32_t stored_crc = 0;
    uint32_t stored_version = 0;
    size_t blob_size = 0;
    bool invalid = false;

    err = nvs_get_u32(
        handle,
        ROUTE_NVS_VERSION_KEY,
        &stored_version
    );

    if (err != ESP_OK || stored_version != ROUTE_NVS_VERSION) {
        ESP_LOGE(
            TAG,
            "Persisted route version invalid: %s, value=%lu",
            esp_err_to_name(err),
            (unsigned long)stored_version
        );
        invalid = true;
    }

    if (!invalid) {
        err = nvs_get_u32(
            handle,
            ROUTE_NVS_SIZE_KEY,
            &stored_size
        );

        if (err != ESP_OK ||
            stored_size == 0 ||
            stored_size > ROUTE_BUFFER_SIZE
        ) {
            ESP_LOGE(
                TAG,
                "Persisted route size invalid: %s, value=%lu",
                esp_err_to_name(err),
                (unsigned long)stored_size
            );
            invalid = true;
        }
    }

    if (!invalid) {
        err = nvs_get_blob(
            handle,
            ROUTE_NVS_BLOB_KEY,
            NULL,
            &blob_size
        );

        if (err != ESP_OK || blob_size != stored_size) {
            ESP_LOGE(
                TAG,
                "Persisted route blob invalid: %s, blob=%u, metadata=%lu",
                esp_err_to_name(err),
                (unsigned int)blob_size,
                (unsigned long)stored_size
            );
            invalid = true;
        }
    }

    if (!invalid) {
        err = nvs_get_u32(
            handle,
            ROUTE_NVS_CRC_KEY,
            &stored_crc
        );

        if (err != ESP_OK) {
            ESP_LOGE(
                TAG,
                "Persisted route CRC read failed: %s",
                esp_err_to_name(err)
            );
            invalid = true;
        }
    }

    if (!invalid) {
        err = nvs_get_blob(
            handle,
            ROUTE_NVS_BLOB_KEY,
            route_buffer,
            &blob_size
        );

        if (err != ESP_OK) {
            ESP_LOGE(
                TAG,
                "Persisted route blob read failed: %s",
                esp_err_to_name(err)
            );
            invalid = true;
        }
    }

    nvs_close(handle);

    if (invalid) {
        if (route_persistence_erase() != ESP_OK) {
            ESP_LOGE(TAG, "Failed to erase invalid persisted route");
        }
        return false;
    }

    uint32_t calculated_crc =
        crc32_update(
            0xFFFFFFFF,
            route_buffer,
            stored_size
        ) ^ 0xFFFFFFFF;

    if (calculated_crc != stored_crc) {
        ESP_LOGE(
            TAG,
            "Persisted route CRC mismatch: stored=0x%08lX calculated=0x%08lX",
            (unsigned long)stored_crc,
            (unsigned long)calculated_crc
        );

        if (route_persistence_erase() != ESP_OK) {
            ESP_LOGE(TAG, "Failed to erase invalid persisted route");
        }
        return false;
    }

    route_received_bytes = stored_size;
    route_expected_bytes = stored_size;
    route_crc = crc32_update(
        0xFFFFFFFF,
        route_buffer,
        stored_size
    );

    if (parse_route() != 0) {
        ESP_LOGE(TAG, "Persisted route parsing FAILED");
        if (route_persistence_erase() != ESP_OK) {
            ESP_LOGE(TAG, "Failed to erase invalid persisted route");
        }
        return false;
    }

    navigation_reset();
    route_state_set(ROUTE_STATE_READY);

    ESP_LOGI(
        TAG,
        "Restored valid route from NVS: %lu bytes, CRC=0x%08lX",
        (unsigned long)stored_size,
        (unsigned long)stored_crc
    );

    return true;
}


/* =========================================================
 * Route status string
 * ========================================================= */

static const char *route_state_string(void)
{
    switch (route_state_get()) {

        case ROUTE_STATE_IDLE:
            return "IDLE";

        case ROUTE_STATE_RECEIVING:
            return "RECEIVING";

        case ROUTE_STATE_VERIFYING:
            return "VERIFYING";

        case ROUTE_STATE_READY:
            return "ROUTE_READY";

        case ROUTE_STATE_ERROR:
            return "ERROR";

        default:
            return "UNKNOWN";
    }
}


/* =========================================================
 * Handle START_ROUTE
 *
 * Payload:
 *   4-byte expected route size
 * ========================================================= */

static void handle_start_route(
    const uint8_t *payload,
    uint16_t length)
{
    if (length != 4) {

        ESP_LOGE(
            TAG,
            "START_ROUTE requires 4-byte payload"
        );

        route_state_set(
            ROUTE_STATE_ERROR
        );

        return;
    }


    uint32_t expected =
        read_u32_le(payload);


    if (
        expected == 0 ||
        expected > ROUTE_BUFFER_SIZE
    ) {

        ESP_LOGE(
            TAG,
            "Invalid route size: %lu bytes",
            (unsigned long)expected
        );

        route_state_set(
            ROUTE_STATE_ERROR
        );

        return;
    }


    route_reset();

    route_expected_bytes = expected;


    route_state_set(
        ROUTE_STATE_RECEIVING
    );


    ESP_LOGI(
        TAG,
        "START_ROUTE"
    );

    ESP_LOGI(
        TAG,
        "Expected route data: %lu bytes",
        (unsigned long)route_expected_bytes
    );
}


/* =========================================================
 * Handle ROUTE_INFO
 * ========================================================= */

static void handle_route_info(
    const uint8_t *payload,
    uint16_t length)
{
    if (route_state_get() != ROUTE_STATE_RECEIVING) {

        ESP_LOGE(
            TAG,
            "ROUTE_INFO received outside route session"
        );

        route_state_set(
            ROUTE_STATE_ERROR
        );

        return;
    }

    ESP_LOGI(
        TAG,
        "ROUTE_INFO received: %u bytes",
        length
    );

    /*
     * ROUTE_INFO is metadata only.
     *
     * It must NOT be appended to route_buffer.
     * The actual route binary is received through
     * ROUTE_DATA.
     */

    (void)payload;
}


/* =========================================================
 * Send ACK
 * ========================================================= */

static void send_ack(uint16_t sequence)
{
    if (
        connection_handle ==
        BLE_HS_CONN_HANDLE_NONE
    ) {

        ESP_LOGW(
            TAG,
            "Cannot send ACK: not connected"
        );

        return;
    }


    char message[32];


    int len =
        snprintf(
            message,
            sizeof(message),
            "ACK,%u",
            sequence
        );


    struct os_mbuf *om =
        ble_hs_mbuf_from_flat(
            message,
            len
        );


    if (om == NULL) {

        ESP_LOGE(
            TAG,
            "Failed to allocate ACK buffer"
        );

        return;
    }


    int rc =
        ble_gatts_notify_custom(
            connection_handle,
            status_val_handle,
            om
        );


    if (rc != 0) {

        ESP_LOGE(
            TAG,
            "Failed to send ACK #%u: %d",
            sequence,
            rc
        );

        return;
    }


    ESP_LOGI(
        TAG,
        "ACK sent: #%u",
        sequence
    );
}


/* =========================================================
 * Handle ROUTE_DATA
 * ========================================================= */

static void handle_route_data(
    uint16_t sequence,
    const uint8_t *payload,
    uint16_t length)
{
    if (
        route_state_get() !=
        ROUTE_STATE_RECEIVING
    ) {

        ESP_LOGE(
            TAG,
            "ROUTE_DATA received while not receiving"
        );

        route_state_set(
            ROUTE_STATE_ERROR
        );

        return;
    }


    /* -----------------------------------------------------
     * Sequence check
     * ----------------------------------------------------- */

    if (
        sequence !=
        route_expected_sequence
    ) {

        ESP_LOGE(
            TAG,
            "Sequence error: expected %u, received %u",
            route_expected_sequence,
            sequence
        );

        route_state_set(
            ROUTE_STATE_ERROR
        );

        return;
    }


    /* -----------------------------------------------------
     * Check expected total size
     * ----------------------------------------------------- */

    if (
        route_received_bytes + length >
        route_expected_bytes
    ) {

        ESP_LOGE(
            TAG,
            "Route data exceeds expected size"
        );

        route_state_set(
            ROUTE_STATE_ERROR
        );

        return;
    }


    /* -----------------------------------------------------
     * Check buffer size
     * ----------------------------------------------------- */

    if (
        route_received_bytes + length >
        ROUTE_BUFFER_SIZE
    ) {

        ESP_LOGE(
            TAG,
            "Route buffer overflow"
        );

        route_state_set(
            ROUTE_STATE_ERROR
        );

        return;
    }


    /* -----------------------------------------------------
     * Store bytes
     * ----------------------------------------------------- */

    memcpy(
        &route_buffer[route_received_bytes],
        payload,
        length
    );


    route_received_bytes += length;


    /* -----------------------------------------------------
     * Update complete-route CRC
     * ----------------------------------------------------- */

    route_crc =
        crc32_update(
            route_crc,
            payload,
            length
        );


    route_expected_sequence++;


    ESP_LOGI(
        TAG,
        "ROUTE_DATA #%u: %u bytes",
        sequence,
        length
    );


    send_ack(sequence);


    ESP_LOGI(
        TAG,
        "Total received: %lu / %lu bytes",
        (unsigned long)route_received_bytes,
        (unsigned long)route_expected_bytes
    );
}


/* =========================================================
 * Handle END_ROUTE
 *
 * Payload:
 *   4-byte expected complete-route CRC
 * ========================================================= */

static void handle_end_route(
    const uint8_t *payload,
    uint16_t length)
{
    if (route_state_get() != ROUTE_STATE_RECEIVING) {

        ESP_LOGW(
            TAG,
            "END_ROUTE ignored outside receiving session: %s",
            route_state_string()
        );

        return;
    }

    if (length != 4) {

        ESP_LOGE(
            TAG,
            "END_ROUTE requires 4-byte CRC"
        );

        route_state_set(
            ROUTE_STATE_ERROR
        );

        return;
    }


    route_state_set(
        ROUTE_STATE_VERIFYING
    );


    uint32_t expected_crc =
        read_u32_le(payload);


    uint32_t calculated_crc =
        route_crc ^ 0xFFFFFFFF;


    ESP_LOGI(
        TAG,
        "END_ROUTE received"
    );

    ESP_LOGI(
        TAG,
        "Received bytes: %lu",
        (unsigned long)route_received_bytes
    );

    ESP_LOGI(
        TAG,
        "Expected bytes: %lu",
        (unsigned long)route_expected_bytes
    );

    ESP_LOGI(
        TAG,
        "Expected CRC:   0x%08lX",
        (unsigned long)expected_crc
    );

    ESP_LOGI(
        TAG,
        "Calculated CRC: 0x%08lX",
        (unsigned long)calculated_crc
    );


    /* -----------------------------------------------------
     * Length verification
     * ----------------------------------------------------- */

    if (
        route_received_bytes !=
        route_expected_bytes
    ) {

        ESP_LOGE(
            TAG,
            "Route length verification FAILED"
        );

        route_state_set(
            ROUTE_STATE_ERROR
        );

        return;
    }


    /* -----------------------------------------------------
     * CRC verification
     * ----------------------------------------------------- */

    if (
        calculated_crc !=
        expected_crc
    ) {

        ESP_LOGE(
            TAG,
            "Route CRC verification FAILED"
        );

        route_state_set(
            ROUTE_STATE_ERROR
        );

        return;
    }


    /* -----------------------------------------------------
     * Parse binary route
     * ----------------------------------------------------- */

    log_internal_heap("before route parsing");

    if (
        parse_route() != 0
    ) {

        ESP_LOGE(
            TAG,
            "Route format parsing FAILED"
        );

        route_state_set(
            ROUTE_STATE_ERROR
        );

        return;
    }


    if (route_persistence_save(calculated_crc) != ESP_OK) {
        ESP_LOGW(
            TAG,
            "Route persistence failed; keeping verified RAM route usable"
        );
    }


    /* -----------------------------------------------------
     * Route is valid
     * ----------------------------------------------------- */

    route_state_set(
        ROUTE_STATE_READY
    );


    ESP_LOGI(
        TAG,
        "================================"
    );

    ESP_LOGI(
        TAG,
        "ROUTE READY"
    );

    ESP_LOGI(
        TAG,
        "Route verified successfully"
    );

    ESP_LOGI(
        TAG,
        "================================"
    );


    ESP_LOGI(
        TAG,
        "Stored route data:"
    );


    ESP_LOG_BUFFER_HEXDUMP(
        TAG,
        route_buffer,
        route_received_bytes,
        ESP_LOG_INFO
    );


    print_route();
}


/* =========================================================
 * CONTROL characteristic
 * ========================================================= */

static void handle_control(
    const uint8_t *data,
    uint16_t length)
{
    if (length < 1) {

        ESP_LOGE(
            TAG,
            "Empty CONTROL command"
        );

        return;
    }


    uint8_t command =
        data[0];


    switch (command) {

        case CMD_START_ROUTE:

            if (length != 5) {

                ESP_LOGE(
                    TAG,
                    "START command requires 4-byte size"
                );

                route_state_set(
                    ROUTE_STATE_ERROR
                );

                return;
            }


            handle_start_route(
                &data[1],
                4
            );

            break;


        case CMD_END_ROUTE:

            if (length != 5) {

                ESP_LOGE(
                    TAG,
                    "END command requires 4-byte CRC"
                );

                route_state_set(
                    ROUTE_STATE_ERROR
                );

                return;
            }


            handle_end_route(
                &data[1],
                4
            );

            break;


        case CMD_CANCEL_ROUTE:

            ESP_LOGI(
                TAG,
                "Route transfer cancelled"
            );

            esp_err_t erase_err = route_persistence_erase();

            if (erase_err != ESP_OK) {
                ESP_LOGE(
                    TAG,
                    "Cancellation incomplete; failed to erase persisted route: %s",
                    esp_err_to_name(erase_err)
                );
                break;
            }

            route_reset();

            break;


        case CMD_GET_STATUS:

            ESP_LOGI(
                TAG,
                "Current state: %s",
                route_state_string()
            );

            ESP_LOGI(
                TAG,
                "Received: %lu bytes",
                (unsigned long)route_received_bytes
            );

            break;


        default:

            ESP_LOGE(
                TAG,
                "Unknown CONTROL command: 0x%02X",
                command
            );

            route_state_set(
                ROUTE_STATE_ERROR
            );

            break;
    }
}


/* =========================================================
 * GATT access callback
 * ========================================================= */

static int motonav_gatt_access(
    uint16_t conn_handle,
    uint16_t attr_handle,
    struct ble_gatt_access_ctxt *ctxt,
    void *arg)
{
    ESP_LOGI(
        TAG,
        "GATT ACCESS: conn=%u attr=%u op=%d",
        conn_handle,
        attr_handle,
        ctxt->op
    );
    const ble_uuid_t *uuid =
        ctxt->chr->uuid;


    (void)conn_handle;
    (void)attr_handle;
    (void)arg;


    /* =====================================================
     * CONTROL
     * ===================================================== */

    if (
        ble_uuid_cmp(
            uuid,
            CONTROL_UUID
        ) == 0
    ) {

        if (
            ctxt->op ==
            BLE_GATT_ACCESS_OP_WRITE_CHR
        ) {

            uint8_t buffer[64];


            uint16_t len =
                OS_MBUF_PKTLEN(ctxt->om);


            if (
                len >
                sizeof(buffer)
            ) {

                ESP_LOGE(
                    TAG,
                    "CONTROL packet too large"
                );

                return BLE_ATT_ERR_INVALID_ATTR_VALUE_LEN;
            }


            ble_hs_mbuf_to_flat(
                ctxt->om,
                buffer,
                len,
                NULL
            );


            ESP_LOGI(
                TAG,
                "CONTROL packet received: %u bytes",
                len
            );


            handle_control(
                buffer,
                len
            );


            return 0;
        }


        return BLE_ATT_ERR_READ_NOT_PERMITTED;
    }


    /* =====================================================
     * ROUTE_DATA
     * ===================================================== */

    if (
        ble_uuid_cmp(
            uuid,
            ROUTE_DATA_UUID
        ) == 0
    ) {

        if (
            ctxt->op ==
            BLE_GATT_ACCESS_OP_WRITE_CHR
        ) {

            uint8_t buffer[MAX_PACKET_SIZE];


            uint16_t len =
                OS_MBUF_PKTLEN(ctxt->om);


            if (
                len >
                sizeof(buffer)
            ) {

                ESP_LOGE(
                    TAG,
                    "ROUTE_DATA packet too large: %u",
                    len
                );

                return BLE_ATT_ERR_INVALID_ATTR_VALUE_LEN;
            }


            ble_hs_mbuf_to_flat(
                ctxt->om,
                buffer,
                len,
                NULL
            );


            /* ------------------------------------------------
             * Minimum packet:
             *
             * header = 6
             * CRC    = 4
             * ------------------------------------------------ */

            if (
                len <
                PACKET_HEADER_SIZE +
                PACKET_CRC_SIZE
            ) {

                ESP_LOGE(
                    TAG,
                    "ROUTE_DATA packet too short"
                );

                route_state_set(
                    ROUTE_STATE_ERROR
                );

                return BLE_ATT_ERR_INVALID_ATTR_VALUE_LEN;
            }


            /* ------------------------------------------------
             * Parse packet header
             * ------------------------------------------------ */

            uint8_t type =
                buffer[0];


            uint16_t sequence =
                read_u16_le(
                    &buffer[1]
                );


            uint16_t payload_length =
                read_u16_le(
                    &buffer[3]
                );


            uint8_t flags =
                buffer[5];


            (void)flags;


            /* ------------------------------------------------
             * Packet length check
             * ------------------------------------------------ */

            uint16_t expected_length =
                PACKET_HEADER_SIZE +
                payload_length +
                PACKET_CRC_SIZE;


            if (
                expected_length != len
            ) {

                ESP_LOGE(
                    TAG,
                    "Packet length mismatch"
                );

                ESP_LOGE(
                    TAG,
                    "Header payload length: %u",
                    payload_length
                );

                ESP_LOGE(
                    TAG,
                    "Actual packet length: %u",
                    len
                );

                route_state_set(
                    ROUTE_STATE_ERROR
                );

                return BLE_ATT_ERR_INVALID_ATTR_VALUE_LEN;
            }


            /* ------------------------------------------------
             * Read packet CRC
             * ------------------------------------------------ */

            uint32_t received_crc =
                read_u32_le(
                    &buffer[
                        PACKET_HEADER_SIZE +
                        payload_length
                    ]
                );


            /* ------------------------------------------------
             * Calculate packet CRC
             *
             * CRC covers:
             *
             * header + payload
             * ------------------------------------------------ */

            uint32_t calculated_crc =
                crc32_update(
                    0xFFFFFFFF,
                    buffer,
                    PACKET_HEADER_SIZE +
                    payload_length
                );


            calculated_crc ^=
                0xFFFFFFFF;


            if (
                received_crc !=
                calculated_crc
            ) {

                ESP_LOGE(
                    TAG,
                    "PACKET CRC ERROR"
                );

                ESP_LOGE(
                    TAG,
                    "Expected: 0x%08lX",
                    (unsigned long)calculated_crc
                );

                ESP_LOGE(
                    TAG,
                    "Received: 0x%08lX",
                    (unsigned long)received_crc
                );

                route_state_set(
                    ROUTE_STATE_ERROR
                );

                return 0;
            }


            ESP_LOGI(
                TAG,
                "Valid route packet:"
            );

            ESP_LOGI(
                TAG,
                "  Type:     0x%02X",
                type
            );

            ESP_LOGI(
                TAG,
                "  Sequence: %u",
                sequence
            );

            ESP_LOGI(
                TAG,
                "  Payload:  %u bytes",
                payload_length
            );


            /* ------------------------------------------------
             * Process packet
             * ------------------------------------------------ */

            switch (type) {

                case PACKET_ROUTE_INFO:

                    handle_route_info(
                        &buffer[PACKET_HEADER_SIZE],
                        payload_length
                    );

                    break;


                case PACKET_ROUTE_DATA:

                    handle_route_data(
                        sequence,
                        &buffer[PACKET_HEADER_SIZE],
                        payload_length
                    );

                    break;


                default:

                    ESP_LOGW(
                        TAG,
                        "Unknown ROUTE_DATA packet type: 0x%02X",
                        type
                    );

                    break;
            }


            return 0;
        }


        return BLE_ATT_ERR_READ_NOT_PERMITTED;
    }


    /* =====================================================
     * STATUS
     * ===================================================== */

    if (
        ble_uuid_cmp(
            uuid,
            STATUS_UUID
        ) == 0
    ) {

        if (
            ctxt->op ==
            BLE_GATT_ACCESS_OP_READ_CHR
        ) {

            char status[128];


            snprintf(
                status,
                sizeof(status),
                "%s,%lu/%lu",
                route_state_string(),
                (unsigned long)route_received_bytes,
                (unsigned long)route_expected_bytes
            );


            int rc =
                os_mbuf_append(
                    ctxt->om,
                    status,
                    strlen(status)
                );


            return
                rc == 0
                    ? 0
                    : BLE_ATT_ERR_INSUFFICIENT_RES;
        }


        return BLE_ATT_ERR_WRITE_NOT_PERMITTED;
    }


    return BLE_ATT_ERR_UNLIKELY;
}

static int ble_security_passkey_action(
    struct ble_gap_event *event)
{
    struct ble_sm_io io = {0};

    ESP_LOGI(
        TAG,
        "BLE SECURITY: pairing started"
    );

    if (event->passkey.params.action != BLE_SM_IOACT_DISP) {
        ESP_LOGE(
            TAG,
            "BLE SECURITY: unsupported passkey action=%u",
            event->passkey.params.action
        );
        pairing_ui_set(
            PAIRING_UI_FAILED,
            0,
            pdMS_TO_TICKS(5000)
        );
        return BLE_HS_EAPP;
    }

    io.action = BLE_SM_IOACT_DISP;
    io.passkey = esp_random() % 1000000;

    pairing_ui_set(
        PAIRING_UI_CODE,
        io.passkey,
        pdMS_TO_TICKS(60000)
    );

    ESP_LOGI(
        TAG,
        "BLE SECURITY: passkey %06" PRIu32,
        io.passkey
    );

    int rc = ble_sm_inject_io(
        event->passkey.conn_handle,
        &io
    );

    if (rc != 0) {
        ESP_LOGE(
            TAG,
            "BLE SECURITY: passkey injection failed rc=%d",
            rc
        );
        pairing_ui_set(
            PAIRING_UI_FAILED,
            0,
            pdMS_TO_TICKS(5000)
        );
    }

    return 0;
}

static void ble_security_configure(void)
{
    ble_hs_cfg.store_status_cb =
        ble_store_util_status_rr;

    ble_hs_cfg.sm_io_cap =
        BLE_SM_IO_CAP_DISP_ONLY;
    ble_hs_cfg.sm_bonding = 1;
    ble_hs_cfg.sm_mitm = 1;
    ble_hs_cfg.sm_sc = 1;
    ble_hs_cfg.sm_our_key_dist |=
        BLE_SM_PAIR_KEY_DIST_ENC |
        BLE_SM_PAIR_KEY_DIST_ID;
    ble_hs_cfg.sm_their_key_dist |=
        BLE_SM_PAIR_KEY_DIST_ENC |
        BLE_SM_PAIR_KEY_DIST_ID;
}


/* =========================================================
 * GATT service definition
 * ========================================================= */

static const struct ble_gatt_svc_def gatt_svcs[] = {

    {
        .type =
            BLE_GATT_SVC_TYPE_PRIMARY,

        .uuid =
            MOTONAV_SERVICE_UUID,

        .characteristics =
            (struct ble_gatt_chr_def[]) {

                /* CONTROL */

                {
                    .uuid =
                        CONTROL_UUID,

                    .access_cb =
                        motonav_gatt_access,

                    .flags =
                        BLE_GATT_CHR_F_WRITE |
                        BLE_GATT_CHR_F_WRITE_NO_RSP |
                        BLE_GATT_CHR_F_WRITE_ENC |
                        BLE_GATT_CHR_F_WRITE_AUTHEN,
                },


                /* STATUS */

                {
                    .uuid =
                        STATUS_UUID,

                    .access_cb =
                        motonav_gatt_access,

                    .val_handle =
                        &status_val_handle,

                    .flags =
                        BLE_GATT_CHR_F_READ |
                        BLE_GATT_CHR_F_NOTIFY,
                },


                /* ROUTE_DATA */

                {
                    .uuid =
                        ROUTE_DATA_UUID,

                    .access_cb =
                        motonav_gatt_access,

                    .flags =
                        BLE_GATT_CHR_F_WRITE |
                        BLE_GATT_CHR_F_WRITE_NO_RSP |
                        BLE_GATT_CHR_F_WRITE_ENC |
                        BLE_GATT_CHR_F_WRITE_AUTHEN,
                },


                /* End */

                {
                    0
                }
            },
    },


    /* End */

    {
        0
    }
};


/* =========================================================
 * GAP event handler
 * ========================================================= */

static void start_advertising(void);

static int gap_event_handler(
    struct ble_gap_event *event,
    void *arg)
{
    (void)arg;


    switch (event->type) {

        case BLE_GAP_EVENT_CONNECT:

            if (
                event->connect.status ==
                0
            ) {

                connection_handle =
                    event->connect.conn_handle;


                ESP_LOGI(
                    TAG,
                    "BLE CONNECTED"
                );


                ESP_LOGI(
                    TAG,
                    "Connection handle: %d",
                    connection_handle
                );

            } else {

                ESP_LOGW(
                    TAG,
                    "BLE connection failed: %d",
                    event->connect.status
                );


                connection_handle =
                    BLE_HS_CONN_HANDLE_NONE;


                start_advertising();
            }

            return 0;


        case BLE_GAP_EVENT_DISCONNECT:

            ESP_LOGI(
                TAG,
                "BLE DISCONNECTED"
            );

            ESP_LOGI(
    TAG,
    "Disconnect reason: %d",
    event->disconnect.reason
);


            connection_handle =
                BLE_HS_CONN_HANDLE_NONE;

            pairing_ui_set(
                PAIRING_UI_NONE,
                0,
                0
            );

            route_state_t disconnected_state =
                route_state_get();

            if (
                disconnected_state == ROUTE_STATE_RECEIVING ||
                disconnected_state == ROUTE_STATE_VERIFYING
            ) {

                ESP_LOGI(
                    TAG,
                    "Resetting incomplete route transfer"
                );

                route_reset();
            }


            ESP_LOGI(
                TAG,
                "Restarting advertising..."
            );


            start_advertising();


            return 0;

        case BLE_GAP_EVENT_ENC_CHANGE: {
            struct ble_gap_conn_desc desc;

            if (event->enc_change.status != 0) {
                ESP_LOGE(
                    TAG,
                    "BLE SECURITY: authentication failed rc=%d",
                    event->enc_change.status
                );
                pairing_ui_set(
                    PAIRING_UI_FAILED,
                    0,
                    pdMS_TO_TICKS(5000)
                );
                return 0;
            }

            int rc = ble_gap_conn_find(
                event->enc_change.conn_handle,
                &desc
            );

            if (rc == 0) {
                ESP_LOGI(
                    TAG,
                    "BLE SECURITY: encrypted/authenticated=%u/%u bonded=%u",
                    desc.sec_state.encrypted,
                    desc.sec_state.authenticated,
                    desc.sec_state.bonded
                );

                if (desc.sec_state.authenticated &&
                    desc.sec_state.bonded) {
                    ESP_LOGI(
                        TAG,
                        "BLE SECURITY: authentication complete; peer bond stored"
                    );
                    pairing_ui_set(
                        PAIRING_UI_AUTHENTICATED,
                        0,
                        pdMS_TO_TICKS(3000)
                    );
                }
            }

            return 0;
        }

        case BLE_GAP_EVENT_PASSKEY_ACTION:
            return ble_security_passkey_action(event);

        case BLE_GAP_EVENT_REPEAT_PAIRING: {
            struct ble_gap_conn_desc desc;
            int rc = ble_gap_conn_find(
                event->repeat_pairing.conn_handle,
                &desc
            );

            if (rc != 0) {
                ESP_LOGE(
                    TAG,
                    "BLE SECURITY: repeat pairing lookup failed rc=%d",
                    rc
                );
                return BLE_GAP_REPEAT_PAIRING_IGNORE;
            }

            rc = ble_store_util_delete_peer(
                &desc.peer_id_addr
            );

            if (rc != 0) {
                ESP_LOGE(
                    TAG,
                    "BLE SECURITY: repeat pairing bond delete failed rc=%d",
                    rc
                );
                return BLE_GAP_REPEAT_PAIRING_IGNORE;
            }

            ESP_LOGI(
                TAG,
                "BLE SECURITY: retrying pairing for existing peer"
            );
            return BLE_GAP_REPEAT_PAIRING_RETRY;
        }

                case BLE_GAP_EVENT_SUBSCRIBE:

            ESP_LOGI(
                TAG,
                "BLE SUBSCRIBE EVENT"
            );

            ESP_LOGI(
                TAG,
                "  Connection handle: %d",
                event->subscribe.conn_handle
            );

            ESP_LOGI(
                TAG,
                "  Attribute handle: %d",
                event->subscribe.attr_handle
            );

            ESP_LOGI(
                TAG,
                "  Reason: %d",
                event->subscribe.reason
            );

            ESP_LOGI(
                TAG,
                "  Cur notify: %d",
                event->subscribe.cur_notify
            );

            ESP_LOGI(
                TAG,
                "  Cur indicate: %d",
                event->subscribe.cur_indicate
            );

            return 0;    


        case BLE_GAP_EVENT_ADV_COMPLETE:

            ESP_LOGI(
                TAG,
                "Advertising complete"
            );

            return 0;


        default:

            return 0;
    }
}


/* =========================================================
 * Start BLE advertising
 * ========================================================= */

static void start_advertising(void)
{
    int rc;


    struct ble_hs_adv_fields fields;

    memset(
        &fields,
        0,
        sizeof(fields)
    );


    const char *device_name =
        "MotoNav-01";


    /* Advertising flags */

    fields.flags =
        BLE_HS_ADV_F_DISC_GEN |
        BLE_HS_ADV_F_BREDR_UNSUP;


    /* Device name */

    fields.name =
        (uint8_t *)device_name;

    fields.name_len =
        strlen(device_name);

    fields.name_is_complete =
        1;


    /* Configure advertising data */

    rc =
        ble_gap_adv_set_fields(
            &fields
        );


    if (rc != 0) {

        ESP_LOGE(
            TAG,
            "ble_gap_adv_set_fields failed: %d",
            rc
        );

        return;
    }


    /* Advertising parameters */

    struct ble_gap_adv_params adv_params;

    memset(
        &adv_params,
        0,
        sizeof(adv_params)
    );


    /* Connectable */

    adv_params.conn_mode =
        BLE_GAP_CONN_MODE_UND;


    /* General discoverable */

    adv_params.disc_mode =
        BLE_GAP_DISC_MODE_GEN;


    /* Channels 37, 38, 39 */

    adv_params.channel_map =
        0x07;


    /* 100-200 ms */

    adv_params.itvl_min =
        160;

    adv_params.itvl_max =
        320;


    /* Start */

    rc =
        ble_gap_adv_start(
            own_addr_type,
            NULL,
            BLE_HS_FOREVER,
            &adv_params,
            gap_event_handler,
            NULL
        );


    if (rc != 0) {

        ESP_LOGE(
            TAG,
            "ble_gap_adv_start failed: %d",
            rc
        );

        return;
    }


    ESP_LOGI(
        TAG,
        "BLE advertising started"
    );


    ESP_LOGI(
        TAG,
        "Device name: %s",
        device_name
    );


    ESP_LOGI(
        TAG,
        "Advertising channels: 37, 38, 39"
    );


    ESP_LOGI(
        TAG,
        "Advertising interval: 100-200 ms"
    );
}


/* =========================================================
 * NimBLE synchronization
 * ========================================================= */

static void ble_on_sync(void)
{
    int rc;


    rc =
        ble_hs_id_infer_auto(
            0,
            &own_addr_type
        );


    if (rc != 0) {

        ESP_LOGE(
            TAG,
            "ble_hs_id_infer_auto failed: %d",
            rc
        );

        return;
    }


    ESP_LOGI(
        TAG,
        "BLE host synchronized"
    );


    start_advertising();
}


/* =========================================================
 * NimBLE host task
 * ========================================================= */

static void ble_host_task(
    void *param)
{
    (void)param;


    ESP_LOGI(
        TAG,
        "NimBLE host task started"
    );


    nimble_port_run();


    nimble_port_freertos_deinit();
}

/* =========================================================
 * GPS UART task
 * ========================================================= */

static void gps_task(void *param)
{
    (void)param;

    const uart_config_t uart_config = {
        .baud_rate = GPS_UART_BAUD_RATE,
        .data_bits = UART_DATA_8_BITS,
        .parity    = UART_PARITY_DISABLE,
        .stop_bits = UART_STOP_BITS_1,
        .flow_ctrl = UART_HW_FLOWCTRL_DISABLE,
#if SOC_UART_SUPPORT_RTSCTS
        .source_clk = UART_SCLK_DEFAULT,
#endif
    };

    ESP_ERROR_CHECK(
        uart_driver_install(
            GPS_UART_PORT,
            GPS_RX_BUFFER_SIZE * 2,
            0,
            0,
            NULL,
            0
        )
    );

    ESP_ERROR_CHECK(
        uart_param_config(
            GPS_UART_PORT,
            &uart_config
        )
    );

    ESP_ERROR_CHECK(
        uart_set_pin(
            GPS_UART_PORT,
            GPS_UART_TX_PIN,
            GPS_UART_RX_PIN,
            UART_PIN_NO_CHANGE,
            UART_PIN_NO_CHANGE
        )
    );

    ESP_LOGI(
        TAG,
        "GPS UART initialized: UART%d, TX=%d, RX=%d, baud=%d",
        GPS_UART_PORT,
        GPS_UART_TX_PIN,
        GPS_UART_RX_PIN,
        GPS_UART_BAUD_RATE
    );

    uint8_t *data =
        (uint8_t *)malloc(GPS_RX_BUFFER_SIZE);

    if (data == NULL) {
        ESP_LOGE(TAG, "Failed to allocate GPS buffer");
        vTaskDelete(NULL);
        return;
    }

    char line[GPS_RX_BUFFER_SIZE];
    size_t line_length = 0;
    bool line_overflow = false;

    while (1) {

        int length = uart_read_bytes(
            GPS_UART_PORT,
            data,
            GPS_RX_BUFFER_SIZE,
            pdMS_TO_TICKS(1000)
        );

        if (length <= 0) {
            continue;
        }

        for (int i = 0; i < length; i++) {

            char c = (char)data[i];

            if (c == '\n') {

                if (line_overflow) {

                    line_overflow = false;
                    line_length = 0;

                } else if (line_length > 0) {

                    line[line_length] = '\0';

                    gps_parse_nmea(line);

                    line_length = 0;
                }

            } else if (!line_overflow && c != '\r') {

                if (line_length <
                    GPS_RX_BUFFER_SIZE - 1) {

                    line[line_length++] = c;

                } else {

                    ESP_LOGW(
                        TAG,
                        "GPS NMEA line too long; resetting"
                    );

                    line_length = 0;
                    line_overflow = true;
                }
            }
        }
    }
}

/* =========================================================
 * Main application
 * ========================================================= */

void app_main(void)
{

    ESP_ERROR_CHECK(
        oled_display_init()
    );

    ESP_LOGI(
        TAG,
        "================================"
    );

    ESP_LOGI(
        TAG,
        "       MotoNav Route Test"
    );

    ESP_LOGI(
        TAG,
        "       Route Format v1"
    );

    ESP_LOGI(
        TAG,
        "================================"
    );


    /* -----------------------------------------------------
     * Initialize NVS
     * ----------------------------------------------------- */

    esp_err_t ret =
        nvs_flash_init();

    if (ret != ESP_OK) {
        ESP_LOGE(
            TAG,
            "NVS initialization failed: %s (%d); "
            "leaving the default NVS partition unchanged",
            esp_err_to_name(ret),
            ret
        );
        return;
    }


    /* -----------------------------------------------------
     * Restore the last verified route, if one exists
     * ----------------------------------------------------- */

    route_reset();

    if (!route_persistence_restore()) {
        route_reset();
    }


    /* -----------------------------------------------------
     * Initialize NimBLE
     * ----------------------------------------------------- */

    nimble_port_init();

    ble_security_configure();


    /* -----------------------------------------------------
     * GAP/GATT
     * ----------------------------------------------------- */

    ble_svc_gap_init();

    ble_svc_gatt_init();


    /* -----------------------------------------------------
     * Device name
     * ----------------------------------------------------- */

    ret =
        ble_svc_gap_device_name_set(
            "MotoNav-01"
        );


    ESP_ERROR_CHECK(ret);


    /* -----------------------------------------------------
     * Configure GATT database
     * ----------------------------------------------------- */

    int rc =
        ble_gatts_count_cfg(
            gatt_svcs
        );


    if (rc != 0) {

        ESP_LOGE(
            TAG,
            "ble_gatts_count_cfg failed: %d",
            rc
        );

        return;
    }


    rc =
        ble_gatts_add_svcs(
            gatt_svcs
        );


    if (rc != 0) {

        ESP_LOGE(
            TAG,
            "ble_gatts_add_svcs failed: %d",
            rc
        );

        return;
    }


    /* -----------------------------------------------------
     * BLE synchronization
     * ----------------------------------------------------- */

    ble_hs_cfg.sync_cb =
        ble_on_sync;


    /* -----------------------------------------------------
     * ----------------------------------------------------- */

    nimble_port_freertos_init(
        ble_host_task
    );

    xTaskCreate(
    navigation_simulation_task,
    "navigation_sim",
    4096,
    NULL,
    5,
    NULL
);

    xTaskCreate(
        oled_navigation_task,
        "oled_navigation",
        4096,
        NULL,
        5,
        NULL
    );

    xTaskCreate(
        gps_task,
        "gps_task",
        4096,
        NULL,
        5,
        NULL
    );

    log_internal_heap("after MotoNav task startup");
    
}
