#include <stdio.h>
#include <string.h>
#include <stdint.h>
#include <stdbool.h>

#include "freertos/FreeRTOS.h"
#include "freertos/task.h"

#include "esp_log.h"
#include "esp_err.h"

#include "driver/i2c_master.h"

#include "oled_display.h"


#define OLED_I2C_PORT       I2C_NUM_0
#define OLED_SDA_GPIO       5
#define OLED_SCL_GPIO       6
#define OLED_I2C_FREQ_HZ    100000


static const char *TAG = "OLED";


static i2c_master_bus_handle_t oled_i2c_bus;
static i2c_master_dev_handle_t oled_i2c_dev;

static uint8_t displayed_frame[OLED_HEIGHT / 8][OLED_WIDTH];
static bool displayed_frame_valid = false;


/*
 * =========================================================
 * 5x7 font
 * =========================================================
 *
 * Characters are stored column-by-column.
 *
 * Supported:
 *   SPACE
 *   0-9
 *   A-Z
 *   :
 *   .
 *   -
 */
static const uint8_t font_5x7[][5] = {

    [' '] = {0x00, 0x00, 0x00, 0x00, 0x00},

    ['0'] = {0x3E, 0x51, 0x49, 0x45, 0x3E},
    ['1'] = {0x00, 0x42, 0x7F, 0x40, 0x00},
    ['2'] = {0x42, 0x61, 0x51, 0x49, 0x46},
    ['3'] = {0x21, 0x41, 0x45, 0x4B, 0x31},
    ['4'] = {0x18, 0x14, 0x12, 0x7F, 0x10},
    ['5'] = {0x27, 0x45, 0x45, 0x45, 0x39},
    ['6'] = {0x3C, 0x4A, 0x49, 0x49, 0x30},
    ['7'] = {0x01, 0x71, 0x09, 0x05, 0x03},
    ['8'] = {0x36, 0x49, 0x49, 0x49, 0x36},
    ['9'] = {0x06, 0x49, 0x49, 0x29, 0x1E},

    ['A'] = {0x7E, 0x11, 0x11, 0x11, 0x7E},
    ['B'] = {0x7F, 0x49, 0x49, 0x49, 0x36},
    ['C'] = {0x3E, 0x41, 0x41, 0x41, 0x22},
    ['D'] = {0x7F, 0x41, 0x41, 0x22, 0x1C},
    ['E'] = {0x7F, 0x49, 0x49, 0x49, 0x41},
    ['F'] = {0x7F, 0x09, 0x09, 0x09, 0x01},
    ['G'] = {0x3E, 0x41, 0x49, 0x49, 0x7A},
    ['H'] = {0x7F, 0x08, 0x08, 0x08, 0x7F},
    ['I'] = {0x00, 0x41, 0x7F, 0x41, 0x00},
    ['J'] = {0x20, 0x40, 0x41, 0x3F, 0x01},
    ['K'] = {0x7F, 0x08, 0x14, 0x22, 0x41},
    ['L'] = {0x7F, 0x40, 0x40, 0x40, 0x40},
    ['M'] = {0x7F, 0x02, 0x0C, 0x02, 0x7F},
    ['N'] = {0x7F, 0x04, 0x08, 0x10, 0x7F},
    ['O'] = {0x3E, 0x41, 0x41, 0x41, 0x3E},
    ['P'] = {0x7F, 0x09, 0x09, 0x09, 0x06},
    ['Q'] = {0x3E, 0x41, 0x51, 0x21, 0x5E},
    ['R'] = {0x7F, 0x09, 0x19, 0x29, 0x46},
    ['S'] = {0x46, 0x49, 0x49, 0x49, 0x31},
    ['T'] = {0x01, 0x01, 0x7F, 0x01, 0x01},
    ['U'] = {0x3F, 0x40, 0x40, 0x40, 0x3F},
    ['V'] = {0x1F, 0x20, 0x40, 0x20, 0x1F},
    ['W'] = {0x7F, 0x20, 0x18, 0x20, 0x7F},
    ['X'] = {0x63, 0x14, 0x08, 0x14, 0x63},
    ['Y'] = {0x07, 0x08, 0x70, 0x08, 0x07},
    ['Z'] = {0x61, 0x51, 0x49, 0x45, 0x43},

    [':'] = {0x00, 0x36, 0x36, 0x00, 0x00},
    ['.'] = {0x00, 0x60, 0x60, 0x00, 0x00},
    ['-'] = {0x08, 0x08, 0x08, 0x08, 0x08},
};


/*
 * =========================================================
 * Send one SSD1306 command
 * =========================================================
 */
static esp_err_t oled_command(uint8_t command)
{
    uint8_t data[2] = {
        0x00,
        command
    };

    return i2c_master_transmit(
        oled_i2c_dev,
        data,
        sizeof(data),
        1000
    );
}


/*
 * =========================================================
 * Send display data
 * =========================================================
 */
static esp_err_t oled_data(
    const uint8_t *data,
    size_t length)
{
    uint8_t buffer[129];

    if (length > 128) {
        return ESP_ERR_INVALID_SIZE;
    }

    buffer[0] = 0x40;

    memcpy(
        &buffer[1],
        data,
        length
    );

    return i2c_master_transmit(
        oled_i2c_dev,
        buffer,
        length + 1,
        1000
    );
}


/*
 * =========================================================
 * Initialize SSD1306
 * =========================================================
 */
static esp_err_t oled_controller_init(void)
{
    const uint8_t init_commands[] = {

        0xAE,

        0xD5, 0x80,
        0xA8, 0x3F,
        0xD3, 0x00,

        0x40,

        0x8D, 0x14,

        0x20, 0x02, /* Page addressing, matching oled_set_position(). */

        0xA1,
        0xC8,

        0xDA, 0x12,

        0x81, 0x7F,

        0xD9, 0xF1,

        0xDB, 0x40,

        0xA4,
        0xA6,

        0xAF
    };


    for (size_t i = 0; i < sizeof(init_commands); ) {

        uint8_t command =
            init_commands[i++];

        ESP_ERROR_CHECK(
            oled_command(command)
        );


        if (
            command == 0xD5 ||
            command == 0xA8 ||
            command == 0xD3 ||
            command == 0x8D ||
            command == 0x20 ||
            command == 0xDA ||
            command == 0x81 ||
            command == 0xD9 ||
            command == 0xDB
        ) {

            ESP_ERROR_CHECK(
                oled_command(
                    init_commands[i++]
                )
            );
        }
    }

    return ESP_OK;
}


/*
 * =========================================================
 * Set page and column
 * =========================================================
 */
static esp_err_t oled_set_position(
    uint8_t page,
    uint8_t column)
{
    esp_err_t err = oled_command(0xB0 | page);
    if (err == ESP_OK) {
        err = oled_command(column & 0x0F);
    }
    if (err == ESP_OK) {
        err = oled_command(0x10 | ((column >> 4) & 0x0F));
    }
    return err;
}


/*
 * =========================================================
 * Clear display
 * =========================================================
 */
esp_err_t oled_display_clear(void)
{
    displayed_frame_valid = false;
    uint8_t blank[128];

    memset(
        blank,
        0x00,
        sizeof(blank)
    );


    for (uint8_t page = 0; page < 8; page++) {

        ESP_ERROR_CHECK(
            oled_set_position(page, 0)
        );

        ESP_ERROR_CHECK(
            oled_data(
                blank,
                sizeof(blank)
            )
        );
    }

    return ESP_OK;
}


/*
 * =========================================================
 * Draw one character
 * =========================================================
 */
static esp_err_t oled_draw_char(
    uint8_t x,
    uint8_t page,
    char c)
{
    uint8_t pixels[6];


    if ((uint8_t)c >= sizeof(font_5x7) / sizeof(font_5x7[0])) {
        c = ' ';
    }


    for (int i = 0; i < 5; i++) {
        pixels[i] =
            font_5x7[(uint8_t)c][i];
    }

    pixels[5] = 0x00;


    ESP_ERROR_CHECK(
        oled_set_position(
            page,
            x
        )
    );


    return oled_data(
        pixels,
        sizeof(pixels)
    );
}


/*
 * =========================================================
 * Draw text
 * =========================================================
 */
esp_err_t oled_display_text(
    uint8_t x,
    uint8_t page,
    const char *text)
{
    if (text == NULL || page >= OLED_HEIGHT / 8) {
        return ESP_ERR_INVALID_ARG;
    }

    displayed_frame_valid = false;

    while (
        *text != '\0' &&
        x <= 122
    ) {

        ESP_ERROR_CHECK(
            oled_draw_char(
                x,
                page,
                *text
            )
        );

        x += 6;
        text++;
    }

    return ESP_OK;
}


/*
 * =========================================================
 * Draw horizontal line
 * =========================================================
 */
esp_err_t oled_display_line(
    uint8_t page)
{
    if (page >= OLED_HEIGHT / 8) {
        return ESP_ERR_INVALID_ARG;
    }
    displayed_frame_valid = false;
    uint8_t line[128];

    memset(
        line,
        0xFF,
        sizeof(line)
    );


    ESP_ERROR_CHECK(
        oled_set_position(
            page,
            0
        )
    );


    return oled_data(
        line,
        sizeof(line)
    );
}


/* Bounds-checked pixel graphics; icons never index the character font. */
static void frame_pixel(uint8_t *frame, int x, int y)
{
    if (x >= 0 && x < OLED_WIDTH && y >= 0 && y < OLED_HEIGHT) {
        frame[(y / 8) * OLED_WIDTH + x] |= (uint8_t)(1U << (y % 8));
    }
}

static void frame_line(uint8_t *frame, int x0, int y0, int x1, int y1)
{
    int dx = x1 > x0 ? x1 - x0 : x0 - x1;
    int dy = y1 > y0 ? y0 - y1 : y1 - y0;
    int sx = x0 < x1 ? 1 : -1;
    int sy = y0 < y1 ? 1 : -1;
    int error = dx + dy;

    while (true) {
        frame_pixel(frame, x0, y0);
        if (x0 == x1 && y0 == y1) {
            break;
        }
        int twice_error = 2 * error;
        if (twice_error >= dy) {
            error += dy;
            x0 += sx;
        }
        if (twice_error <= dx) {
            error += dx;
            y0 += sy;
        }
    }
}

static void frame_text(uint8_t *frame, int y, const char *text)
{
    size_t length = strlen(text);
    int scale = length <= 10 ? 2 : 1;
    if (length > OLED_WIDTH / 6) {
        length = OLED_WIDTH / 6;
    }
    int x = (OLED_WIDTH - (int)length * 6 * scale + scale) / 2;

    for (size_t i = 0; i < length; i++) {
        uint8_t c = (uint8_t)text[i];
        if (c >= sizeof(font_5x7) / sizeof(font_5x7[0])) {
            c = ' ';
        }
        for (int col = 0; col < 5; col++) {
            for (int row = 0; row < 7; row++) {
                if (font_5x7[c][col] & (1U << row)) {
                    for (int yy = 0; yy < scale; yy++) {
                        for (int xx = 0; xx < scale; xx++) {
                            frame_pixel(frame, x + col * scale + xx,
                                        y + row * scale + yy);
                        }
                    }
                }
            }
        }
        x += 6 * scale;
    }
}

esp_err_t oled_display_guidance(
    oled_icon_t icon,
    const char *title,
    const char *detail)
{
    if (title == NULL || detail == NULL) {
        return ESP_ERR_INVALID_ARG;
    }

    uint8_t frame[OLED_HEIGHT / 8][OLED_WIDTH] = {0};
    uint8_t *pixels = &frame[0][0];

    if (icon == OLED_ICON_LEFT || icon == OLED_ICON_RIGHT) {
        /* Thick diagonal shaft and arrowhead, mirrored for left turns. */
        for (int thickness = -2; thickness <= 2; thickness++) {
            int tip = icon == OLED_ICON_LEFT ? 48 : 80;
            int tail = icon == OLED_ICON_LEFT ? 72 : 56;
            int inner = icon == OLED_ICON_LEFT ? 65 : 63;
            frame_line(pixels, tail + thickness, 28, tip + thickness, 4);
            frame_line(pixels, tip, 4 + thickness, inner, 4 + thickness);
            frame_line(pixels, tip + thickness, 4, tip + thickness, 21);
        }
    } else if (icon == OLED_ICON_DESTINATION) {
        /* Five-point star. */
        static const int star[10][2] = {
            {64, 2}, {68, 12}, {80, 12}, {71, 19}, {74, 30},
            {64, 23}, {54, 30}, {57, 19}, {48, 12}, {60, 12}
        };
        for (int i = 0; i < 10; i++) {
            int next = (i + 1) % 10;
            frame_line(pixels, star[i][0], star[i][1],
                       star[next][0], star[next][1]);
            frame_line(pixels, star[i][0] + 1, star[i][1],
                       star[next][0] + 1, star[next][1]);
        }
    }

    frame_text(pixels, icon == OLED_ICON_NONE ? 16 : 34, title);
    frame_text(pixels, 50, detail);

    for (uint8_t page = 0; page < OLED_HEIGHT / 8; page++) {
        if (!displayed_frame_valid ||
            memcmp(frame[page], displayed_frame[page], OLED_WIDTH) != 0) {
            esp_err_t err = oled_set_position(page, 0);
            if (err == ESP_OK) {
                err = oled_data(frame[page], OLED_WIDTH);
            }
            if (err != ESP_OK) {
                displayed_frame_valid = false;
                return err;
            }
            memcpy(displayed_frame[page], frame[page], OLED_WIDTH);
        }
    }
    displayed_frame_valid = true;
    return ESP_OK;
}

esp_err_t oled_display_pairing(
    const char *title,
    const char *code,
    const char *prompt)
{
    if (title == NULL || code == NULL || prompt == NULL) {
        return ESP_ERR_INVALID_ARG;
    }

    uint8_t frame[OLED_HEIGHT / 8][OLED_WIDTH] = {0};
    uint8_t *pixels = &frame[0][0];

    frame_text(pixels, 0, title);
    frame_text(pixels, 24, code);
    frame_text(pixels, 48, prompt);

    for (uint8_t page = 0; page < OLED_HEIGHT / 8; page++) {
        if (!displayed_frame_valid ||
            memcmp(frame[page], displayed_frame[page], OLED_WIDTH) != 0) {
            esp_err_t err = oled_set_position(page, 0);
            if (err == ESP_OK) {
                err = oled_data(frame[page], OLED_WIDTH);
            }
            if (err != ESP_OK) {
                displayed_frame_valid = false;
                return err;
            }
            memcpy(displayed_frame[page], frame[page], OLED_WIDTH);
        }
    }

    displayed_frame_valid = true;
    return ESP_OK;
}

/*
 * =========================================================
 * Initialize I2C
 * =========================================================
 */
esp_err_t oled_display_init(void)
{
    ESP_LOGI(
        TAG,
        "Initializing OLED"
    );

    ESP_LOGI(
        TAG,
        "SDA = GPIO%d",
        OLED_SDA_GPIO
    );

    ESP_LOGI(
        TAG,
        "SCL = GPIO%d",
        OLED_SCL_GPIO
    );

    ESP_LOGI(
        TAG,
        "Address = 0x%02X",
        OLED_I2C_ADDR
    );


    i2c_master_bus_config_t bus_config = {

        .clk_source =
            I2C_CLK_SRC_DEFAULT,

        .i2c_port =
            OLED_I2C_PORT,

        .sda_io_num =
            OLED_SDA_GPIO,

        .scl_io_num =
            OLED_SCL_GPIO,

        .glitch_ignore_cnt = 7,

        .flags.enable_internal_pullup = true,
    };


    ESP_ERROR_CHECK(
        i2c_new_master_bus(
            &bus_config,
            &oled_i2c_bus
        )
    );


    i2c_device_config_t dev_config = {

        .dev_addr_length =
            I2C_ADDR_BIT_LEN_7,

        .device_address =
            OLED_I2C_ADDR,

        .scl_speed_hz =
            OLED_I2C_FREQ_HZ,
    };


    ESP_ERROR_CHECK(
        i2c_master_bus_add_device(
            oled_i2c_bus,
            &dev_config,
            &oled_i2c_dev
        )
    );


    vTaskDelay(
        pdMS_TO_TICKS(100)
    );


    ESP_ERROR_CHECK(
        oled_controller_init()
    );


    ESP_LOGI(
        TAG,
        "SSD1306 initialized"
    );


    ESP_ERROR_CHECK(
        oled_display_clear()
    );


    ESP_LOGI(
        TAG,
        "OLED ready"
    );


    return ESP_OK;
}